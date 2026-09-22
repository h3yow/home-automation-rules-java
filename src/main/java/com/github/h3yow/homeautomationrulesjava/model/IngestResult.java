package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;
import java.util.Objects;

/**
 * 单条上报事件的处理结果，同时承载“可解释的判定依据”。
 * decision 为自然语言说明，例如拒绝原因、忽略原因、接受后触发了哪些规则。
 */
public record IngestResult(
        String eventId,
        IngestOutcomeType outcome,
        RejectReason rejectReason,
        IgnoreReason ignoreReason,
        String decision,
        Object confirmedValue,
        List<RuleFireOutcome> ruleOutcomes
) {
    public IngestResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decision, "decision");
        ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
    }

    public static IngestResult rejected(String eventId, RejectReason reason, String decision) {
        return new IngestResult(eventId, IngestOutcomeType.REJECTED, reason, null, decision, null, List.of());
    }

    public static IngestResult ignored(String eventId, IgnoreReason reason, String decision) {
        return new IngestResult(eventId, IngestOutcomeType.IGNORED, null, reason, decision, null, List.of());
    }

    public static IngestResult accepted(String eventId, Object confirmedValue, String decision,
                                        List<RuleFireOutcome> outcomes) {
        return new IngestResult(eventId, IngestOutcomeType.ACCEPTED, null, null,
                decision, confirmedValue, outcomes);
    }
}
