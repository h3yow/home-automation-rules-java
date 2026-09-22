package com.github.h3yow.homeautomationrulesjava.action;

import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认动作执行器。
 *
 * 保证：
 *  - 动作严格按序执行；每个动作执行前复查条件，条件失效（CONDITION_INVALID）立即停止；
 *  - 单个动作有界重试（默认最多 N 次尝试），同一动作所有尝试复用同一幂等键；
 *  - DEVICE_UNREACHABLE / TIMEOUT / CONDITION_INVALID 等失败原因逐跳记录、可区分；
 *  - 出现失败或条件失效后按序执行补偿动作，补偿同样幂等、有界重试；
 *  - 输出 ALL_SUCCESS / PARTIAL_FAILURE / ALL_FAILED / NOT_EXECUTED 及文字解释。
 */
@SuppressWarnings("FutureReturnValueIgnored")
@Component
public class DefaultActionExecutor implements ActionExecutor {

    /** 单次尝试的超时时间（毫秒）。 */
    private static final long ATTEMPT_TIMEOUT_MILLIS = 200L;
    /** 重试间隔（毫秒，保持测试快速）。 */
    private static final long RETRY_BACKOFF_MILLIS = 5L;

    private final DeviceGateway gateway;
    private final ExecutorService timeoutPool;

    public DefaultActionExecutor(DeviceGateway gateway) {
        this.gateway = gateway;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "action-attempt-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.timeoutPool = Executors.newCachedThreadPool(factory);
    }

    @Override
    public ExecutionReport execute(String triggerId, List<ActionDef> actions,
                                   List<ActionDef> compensations, int maxAttemptsPerAction,
                                   ConditionChecker conditionChecker) {
        int maxAttempts = Math.max(1, maxAttemptsPerAction);
        List<ActionResult> steps = new ArrayList<>();
        // 已成功动作的序号集合：补偿只针对这些动作，避免对未执行/未生效的动作做多余补偿
        java.util.Set<Integer> succeededSeq = new java.util.LinkedHashSet<>();
        boolean conditionInvalid = false;
        FailureKind firstFailure = null;

        for (int i = 0; i < actions.size(); i++) {
            ActionDef action = actions.get(i);
            int seq = i + 1;

            // 动作前复查条件：失效则停止后续动作
            if (conditionChecker != null && !conditionChecker.stillValid()) {
                steps.add(new ActionResult(seq, action.targetDeviceId(), action.value(),
                        ActionStatus.SKIPPED_CONDITION_INVALID, 0,
                        FailureKind.CONDITION_INVALID, key(triggerId, seq)));
                conditionInvalid = true;
                break;
            }

            Attempt attempt = runWithRetry(triggerId, seq, action, maxAttempts);
            steps.add(attempt.result());
            if (attempt.ok()) {
                succeededSeq.add(seq);
            } else {
                if (firstFailure == null) {
                    firstFailure = attempt.result().failureKind();
                }
                // 动作序列在首个最终失败处停止（后面动作不再执行）
                break;
            }
        }

        boolean anyFailure = steps.stream().anyMatch(s -> s.status() == ActionStatus.FAILED_FINAL);

        // 失败或条件失效 -> 仅对已成功动作按序执行补偿；无补偿定义时跳过
        List<ActionResult> compensationSteps = new ArrayList<>();
        if ((anyFailure || conditionInvalid) && !succeededSeq.isEmpty()) {
            int compIndex = 0;
            for (int seq : succeededSeq) {
                if (compIndex >= compensations.size()) {
                    break;
                }
                ActionDef comp = compensations.get(compIndex);
                Attempt attempt = runWithRetry(triggerId + ":comp", seq, comp, maxAttempts);
                ActionResult raw = attempt.result();
                ActionResult marked = new ActionResult(raw.seq(), raw.deviceId(), raw.value(),
                        raw.status() == ActionStatus.SUCCESS ? ActionStatus.COMPENSATED : raw.status(),
                        raw.attempts(), raw.failureKind(), raw.idempotencyKey());
                compensationSteps.add(marked);
                compIndex++;
            }
        }

        ExecutionOutcome outcome;
        if (conditionInvalid && succeededSeq.isEmpty()
                && !steps.isEmpty()
                && steps.get(0).status() == ActionStatus.SKIPPED_CONDITION_INVALID) {
            outcome = ExecutionOutcome.NOT_EXECUTED;
        } else if (!anyFailure && !conditionInvalid && succeededSeq.size() == actions.size()) {
            outcome = ExecutionOutcome.ALL_SUCCESS;
        } else if (succeededSeq.isEmpty()) {
            outcome = ExecutionOutcome.ALL_FAILED;
        } else {
            outcome = ExecutionOutcome.PARTIAL_FAILURE;
        }

        String explanation = buildExplanation(outcome, succeededSeq.size(), actions.size(),
                firstFailure, conditionInvalid, compensationSteps);
        return new ExecutionReport(outcome, List.copyOf(steps),
                List.copyOf(compensationSteps), explanation);
    }

    private Attempt runWithRetry(String triggerId, int seq, ActionDef action, int maxAttempts) {
        String idemKey = key(triggerId, seq);
        FailureKind lastKind = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            ActionCommand command = new ActionCommand(
                    idemKey, action.targetDeviceId(), action.value(), attemptNo);
            try {
                runWithTimeout(command);
                return new Attempt(true, new ActionResult(seq, action.targetDeviceId(),
                        action.value(), ActionStatus.SUCCESS, attemptNo, null, idemKey));
            } catch (ActionException e) {
                lastKind = e.kind();
                if (attemptNo < maxAttempts) {
                    sleep(RETRY_BACKOFF_MILLIS);
                }
            }
        }
        return new Attempt(false, new ActionResult(seq, action.targetDeviceId(),
                action.value(), ActionStatus.FAILED_FINAL, maxAttempts, lastKind, idemKey));
    }

    private void runWithTimeout(ActionCommand command) {
        Future<?> future = timeoutPool.submit(() -> {
            gateway.execute(command);
            return null;
        });
        try {
            future.get(ATTEMPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new ActionException(FailureKind.TIMEOUT,
                    "execution timeout after " + ATTEMPT_TIMEOUT_MILLIS + "ms: "
                            + command.deviceId());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ActionException actionException) {
                throw actionException;
            }
            throw new ActionException(FailureKind.DEVICE_UNREACHABLE,
                    "execution error: " + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionException(FailureKind.TIMEOUT, "interrupted");
        }
    }

    private static String key(String triggerId, int seq) {
        return triggerId + "#" + seq;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildExplanation(ExecutionOutcome outcome, int successCount, int total,
                                    FailureKind firstFailure, boolean conditionInvalid,
                                    List<ActionResult> compensationSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append(outcome.name()).append(": ").append(successCount).append('/').append(total)
                .append(" 动作成功");
        if (firstFailure != null) {
            sb.append("；首个失败原因=").append(firstFailure);
        }
        if (conditionInvalid) {
            sb.append("；动作执行前复查到条件已失效(CONDITION_INVALID)，停止后续动作");
        }
        if (!compensationSteps.isEmpty()) {
            long compOk = compensationSteps.stream()
                    .filter(r -> r.status() == ActionStatus.COMPENSATED).count();
            sb.append("；补偿执行 ").append(compOk).append('/').append(compensationSteps.size())
                    .append(" 成功");
            compensationSteps.stream()
                    .filter(r -> r.status() == ActionStatus.FAILED_FINAL)
                    .findFirst()
                    .ifPresent(r -> sb.append("，补偿失败原因=").append(r.failureKind()));
        }
        return sb.toString();
    }

    private record Attempt(boolean ok, ActionResult result) {
    }
}
