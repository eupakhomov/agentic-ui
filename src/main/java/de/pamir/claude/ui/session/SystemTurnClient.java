package de.pamir.claude.ui.session;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared "run a system-session turn, then make sense of the reply" pipeline. Before this
 * extraction, ReflectionService/ServiceDiscoveryService/TicketImportService/LibraryAiService/
 * GitAssistService each hand-rolled their own {@code stripFences}/{@code truncate} plus a
 * runSystemTurn→stripFences→mapper.readTree→validate flow (HandoffService is the one text-only
 * consumer) — see docs/plan/phase-9-production-hardening.md G1.
 */
@Service
public class SystemTurnClient {

	private final SessionService sessionService;
	private final ObjectMapper mapper;

	public SystemTurnClient(SessionService sessionService, ObjectMapper mapper) {
		this.sessionService = sessionService;
		this.mapper = mapper;
	}

	/** Runs a system turn and returns the assistant's text verbatim (stripped of surrounding whitespace). */
	public String text(String prompt, Duration timeout) {
		return sessionService.runSystemTurn(prompt, timeout).strip();
	}

	/** Same as {@link #text(String, Duration)} but on a one-off model override — see {@link
	 * SessionService#runSystemTurn(String, String, Duration)}. */
	public String text(String prompt, String modelOverride, Duration timeout) {
		return sessionService.runSystemTurn(prompt, modelOverride, timeout).strip();
	}

	/**
	 * Runs a system turn and parses the reply as JSON, stripping a wrapping ``` fence first.
	 * Throws {@link IllegalStateException} — carrying a truncated preview of the raw reply — if
	 * the result isn't valid JSON. Callers that need to fail soft (log-and-continue rather than
	 * propagate) just catch {@link RuntimeException} around the call instead of the previous
	 * two-stage try/catch.
	 */
	public JsonNode json(String prompt, Duration timeout) {
		return parseJson(sessionService.runSystemTurn(prompt, timeout));
	}

	/** Same as {@link #json(String, Duration)} but on a one-off model override. */
	public JsonNode json(String prompt, String modelOverride, Duration timeout) {
		return parseJson(sessionService.runSystemTurn(prompt, modelOverride, timeout));
	}

	private JsonNode parseJson(String raw) {
		try {
			return mapper.readTree(stripFences(raw));
		} catch (RuntimeException e) {
			throw new IllegalStateException("could not parse system turn response as JSON: " + truncate(raw, 300));
		}
	}

	public static String stripFences(String raw) {
		String cleaned = raw == null ? "" : raw.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
		}
		return cleaned;
	}

	public static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}

	/** Extracts a node's {@code "tags"} array as lowercased, blank-filtered strings — the same
	 * parse duplicated across ServiceDiscoveryService/LibraryAiService/ReflectionService. */
	public static List<String> lowercaseTags(JsonNode node) {
		List<String> tags = new ArrayList<>();
		for (JsonNode tag : node.path("tags")) {
			String value = tag.asText("").strip().toLowerCase(Locale.ROOT);
			if (!value.isBlank()) {
				tags.add(value);
			}
		}
		return tags;
	}
}
