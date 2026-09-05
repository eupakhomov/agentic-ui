package de.pamir.claude.ui.memory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the YAML-frontmatter Markdown files that are semantic memory's source of
 * truth. Deliberately Obsidian-vault-compatible: plain "---" fences, plain keys, tags as a
 * YAML list — nothing Obsidian's own frontmatter parser would choke on.
 */
public final class Frontmatter {

	private static final String FENCE = "---";

	public record Doc(Map<String, Object> meta, String body) {
	}

	private Frontmatter() {
	}

	/** Splits a file's content into its frontmatter map and body; body only (empty meta) if no fence found. */
	public static Doc parse(String content) {
		String normalized = content.replace("\r\n", "\n");
		if (!normalized.startsWith(FENCE + "\n") && !normalized.equals(FENCE)) {
			return new Doc(Map.of(), normalized);
		}
		int end = normalized.indexOf("\n" + FENCE, FENCE.length());
		if (end < 0) {
			return new Doc(Map.of(), normalized);
		}
		String yaml = normalized.substring(FENCE.length() + 1, end);
		String body = normalized.substring(end + FENCE.length() + 1);
		if (body.startsWith("\n")) {
			body = body.substring(1);
		}
		Object loaded = new Yaml().load(yaml);
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
		return new Doc(meta, body);
	}

	/** Renders a doc with a fixed, stable key order so unrelated fields don't reflow on every write. */
	public static String render(String name, String description, List<String> tags, String scope, String servicePath,
								 String updated, String body) {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("name", name);
		meta.put("description", description);
		meta.put("tags", tags);
		meta.put("scope", scope);
		if (servicePath != null) {
			meta.put("service", servicePath);
		}
		meta.put("updated", updated);
		String yaml = new Yaml(options).dump(meta);
		return FENCE + "\n" + yaml + FENCE + "\n\n" + (body == null ? "" : body.strip()) + "\n";
	}
}
