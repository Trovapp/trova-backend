package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 일정 초안 계획–검증–수정 루프(#106 3일차, 설계 B). 정해진 순서가 아니라 검증 결과에 따라 다음 단계가 갈리는 부분만 그래프로 둔다.
 *
 *   START → generate --(실패)--> END
 *                    └→ code_fix → validate --(ERROR 0건 또는 AI 수정 2번 소진)--> END
 *                                          └→ ai_repair → code_fix(다시)
 *
 * AI 수정은 최대 2번(2026-10-03 승인) — 무료 한도(15 RPM)와 응답 시간 때문에. AI가 오히려 나빠지게 고칠 수 있어,
 * 지금까지 ERROR가 가장 적었던 초안을 결과로 쓴다. 상태에는 문자열·정수만 담는다 — LangGraph4j가 노드마다 상태를
 * 자바 직렬화로 복제하는데 SavedPlace 엔티티는 직렬화할 수 없어서(TripReplanGraph와 같은 이유), 장소·날짜는 ThreadLocal로 넘긴다.
 */
@Component
public class TripPlanGraph {

    static final int MAX_AI_REPAIRS = 2;
    // 노드 실행 수: generate 1 + (code_fix·validate) × 3 + ai_repair 2 = 9, 시작·끝 처리 여유를 더한다.
    private static final int RECURSION_LIMIT = 20;

    static final String DRAFT = "draft";
    static final String BEST_DRAFT = "bestDraft";
    static final String BEST_ERRORS = "bestErrors";
    static final String BEST_PROBLEMS = "bestProblems";
    static final String FIRST_ERRORS = "firstErrors";
    static final String ERRORS = "errors";
    static final String GEMINI_CALLS = "geminiCalls";
    static final String REPAIRS = "repairs";
    static final String FIXES = "fixes";
    static final String FAILURE = "failure";
    private static final String SEP = "\n";

    private final DraftGenerator draftGenerator;
    private final CompiledGraph<AgentState> compiled;
    private final ThreadLocal<Context> context = new ThreadLocal<>();

    private record Context(int days, LocalDate startDate, List<SavedPlace> places, List<String> notes,
                           Map<Long, SavedPlace> byId) {
    }

    /**
     * firstErrors: 첫 초안(코드 수정 후)의 ERROR 수, finalErrors: 결과 초안의 ERROR 수 — eval에서 수정 루프의 효과로 쓴다.
     * problems: 결과 초안에 남은 위반(ERROR·WARNING) 문장.
     */
    public record Outcome(Optional<DraftGenerator.Draft> draft, int geminiCalls, int aiRepairs, int firstErrors,
                          int finalErrors, List<String> fixes, List<String> problems, String failure) {
    }

    public TripPlanGraph(DraftGenerator draftGenerator) {
        this.draftGenerator = draftGenerator;
        try {
            this.compiled = build().compile(CompileConfig.builder().recursionLimit(RECURSION_LIMIT).build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("TripPlanGraph 그래프 구성 실패", e);
        }
    }

    public Outcome run(int days, LocalDate startDate, List<SavedPlace> places, List<String> notes) {
        Map<Long, SavedPlace> byId = new HashMap<>();
        places.forEach(p -> byId.put(p.getId(), p));
        context.set(new Context(days, startDate, places, notes, byId));
        try {
            Map<String, Object> init = new HashMap<>();
            init.put(GEMINI_CALLS, 0);
            init.put(REPAIRS, 0);
            init.put(BEST_ERRORS, Integer.MAX_VALUE);
            init.put(FIRST_ERRORS, -1);
            init.put(FIXES, "");
            AgentState end = compiled.invoke(init).orElseThrow(() -> new IllegalStateException("일정 그래프가 결과를 반환하지 않았어요"));
            int calls = intOf(end, GEMINI_CALLS);
            Optional<String> failure = end.<String>value(FAILURE);
            Optional<String> best = end.<String>value(BEST_DRAFT);
            if (failure.isPresent() || best.isEmpty()) {
                return new Outcome(Optional.empty(), calls, intOf(end, REPAIRS), -1, -1, List.of(), List.of(),
                        failure.orElse("초안 없음"));
            }
            return new Outcome(Optional.of(DraftGenerator.fromJson(best.get())), calls, intOf(end, REPAIRS),
                    intOf(end, FIRST_ERRORS), intOf(end, BEST_ERRORS), lines(end.<String>value(FIXES).orElse("")),
                    lines(end.<String>value(BEST_PROBLEMS).orElse("")), null);
        } finally {
            context.remove();
        }
    }

    private StateGraph<AgentState> build() throws GraphStateException {
        return new StateGraph<>(Map.of(), AgentState::new)
                .addNode("generate", node_async(this::generate))
                .addNode("code_fix", node_async(this::codeFix))
                .addNode("validate", node_async(this::validate))
                .addNode("ai_repair", node_async(this::aiRepair))
                .addEdge(START, "generate")
                .addConditionalEdges("generate", edge_async(s -> s.value(FAILURE).isPresent() ? "failed" : "ok"),
                        Map.of("failed", END, "ok", "code_fix"))
                .addEdge("code_fix", "validate")
                .addConditionalEdges("validate", edge_async(this::afterValidate),
                        Map.of("done", END, "repair", "ai_repair"))
                .addEdge("ai_repair", "code_fix");
    }

    String afterValidate(AgentState s) {
        return intOf(s, ERRORS) == 0 || intOf(s, REPAIRS) >= MAX_AI_REPAIRS ? "done" : "repair";
    }

    private Map<String, Object> generate(AgentState s) {
        Context c = context.get();
        DraftGenerator.Result r = draftGenerator.generate(c.days(), c.startDate(), c.places(), c.notes());
        Map<String, Object> out = new HashMap<>();
        out.put(GEMINI_CALLS, intOf(s, GEMINI_CALLS) + r.geminiCalls());
        if (r.draft().isEmpty()) {
            out.put(FAILURE, r.failure() == null ? "초안 없음" : r.failure());
        } else {
            out.put(DRAFT, DraftGenerator.toJson(r.draft().get()));
        }
        return out;
    }

    private Map<String, Object> codeFix(AgentState s) {
        Context c = context.get();
        DraftFixer.Fixed fixed = DraftFixer.fix(DraftGenerator.fromJson(s.<String>value(DRAFT).orElseThrow()), c.byId(), c.startDate());
        return Map.of(DRAFT, DraftGenerator.toJson(fixed.draft()), FIXES, append(s, fixed.fixes()));
    }

    private Map<String, Object> validate(AgentState s) {
        Context c = context.get();
        String json = s.<String>value(DRAFT).orElseThrow();
        DraftValidator.Report report = DraftValidator.validate(DraftGenerator.fromJson(json), c.byId(), c.days(), c.startDate());
        int errors = (int) report.errors();
        Map<String, Object> out = new HashMap<>();
        out.put(ERRORS, errors);
        if (intOf(s, FIRST_ERRORS) < 0) {
            out.put(FIRST_ERRORS, errors);
        }
        if (errors < intOf(s, BEST_ERRORS)) {
            out.put(BEST_ERRORS, errors);
            out.put(BEST_DRAFT, json);
            out.put(BEST_PROBLEMS, String.join(SEP, report.violations().stream().map(DraftValidator.Violation::message).toList()));
        }
        return out;
    }

    private Map<String, Object> aiRepair(AgentState s) {
        Context c = context.get();
        DraftGenerator.Draft current = DraftGenerator.fromJson(s.<String>value(DRAFT).orElseThrow());
        DraftValidator.Report report = DraftValidator.validate(current, c.byId(), c.days(), c.startDate());
        List<String> problems = report.violations().stream()
                .map(v -> (v.severity() == DraftValidator.Severity.ERROR ? "[반드시] " : "[가능하면] ") + v.message())
                .toList();
        DraftGenerator.Result r = draftGenerator.repairRules(c.days(), c.startDate(), c.places(), c.notes(), current, problems);
        int repairs = intOf(s, REPAIRS) + 1;
        Map<String, Object> out = new HashMap<>();
        out.put(REPAIRS, repairs);
        out.put(GEMINI_CALLS, intOf(s, GEMINI_CALLS) + r.geminiCalls());
        if (r.draft().isPresent()) {
            out.put(DRAFT, DraftGenerator.toJson(r.draft().get()));
            out.put(FIXES, append(s, List.of("AI에게 규칙 위반 " + report.errors() + "건 수정을 맡겼어요(" + repairs + "번째).")));
        } else {
            // 형식이 틀린 답은 버리고 이전 초안을 유지한다 — 횟수는 셌으므로 루프는 끝난다.
            out.put(FIXES, append(s, List.of("AI 수정 " + repairs + "번째 응답을 쓰지 못했어요: " + r.failure())));
        }
        return out;
    }

    private static String append(AgentState s, List<String> more) {
        List<String> all = new ArrayList<>(lines(s.<String>value(FIXES).orElse("")));
        all.addAll(more);
        return String.join(SEP, all);
    }

    private static List<String> lines(String joined) {
        return joined.isEmpty() ? List.of() : List.of(joined.split(SEP));
    }

    private static int intOf(AgentState s, String key) {
        return s.<Integer>value(key).orElse(0);
    }
}
