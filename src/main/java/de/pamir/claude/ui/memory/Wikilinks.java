package de.pamir.claude.ui.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts Obsidian-style {@code [[slug]]} / {@code [[slug|alias]]} wikilinks from a doc body. */
public final class Wikilinks {

	private static final Pattern FENCE = Pattern.compile("```.*?```", Pattern.DOTALL);
	private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+?)(?:\\|[^\\]]*)?]]");

	private Wikilinks() {
	}

	/** Distinct target slugs, in first-appearance order; links inside fenced code blocks are ignored. */
	public static List<String> extract(String body) {
		String withoutCode = FENCE.matcher(body == null ? "" : body).replaceAll("");
		LinkedHashSet<String> slugs = new LinkedHashSet<>();
		Matcher m = LINK.matcher(withoutCode);
		while (m.find()) {
			String slug = m.group(1).strip();
			if (!slug.isEmpty()) {
				slugs.add(slug);
			}
		}
		return List.copyOf(slugs);
	}
}
