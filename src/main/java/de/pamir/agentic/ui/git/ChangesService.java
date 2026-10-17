package de.pamir.agentic.ui.git;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * What a session's branch would carry into a PR, for the changes panel (phase 17 C): one list of
 * the working tree versus the merge-base with the base branch — committed, staged, unstaged and
 * untracked changes together, with a {@code where} badge set per file — and a file view (working
 * tree content next to the merge-base content). Read-only by design: there is no write path.
 *
 * <p>Only paths in the current change list are readable ({@link #file}), recomputed on every call:
 * a direct {@code ?path=} can't be used to dump arbitrary worktree files such as the provisioned
 * {@code .claude/settings*.json}, which {@code info/exclude} keeps out of the list.
 */
@Service
public class ChangesService {

	/** Per-file content cap for both sides of the diff. */
	public static final int MAX_FILE_BYTES = 1024 * 1024;
	private static final int BINARY_SNIFF_BYTES = 8000;

	/** {@code mtime}: the working-tree file's last-modified time (epoch ms, 0 if absent) — lets the UI notice a file changed under an open view. */
	public record ChangedFile(String path, String oldPath, String status, List<String> where,
							  int additions, int deletions, long mtime) {
	}

	/** {@code baseKnown} false = the base branch couldn't be resolved, so only uncommitted work is listed. */
	public record ChangeList(String baseBranch, String mergeBase, boolean baseKnown, List<ChangedFile> files) {
	}

	/**
	 * {@code content} is null for a deleted file, {@code baseContent} for an added one. {@code binary}
	 * (NUL bytes, or not valid UTF-8) and {@code truncated} (over {@link #MAX_FILE_BYTES}) files carry
	 * no usable content on the affected side.
	 */
	public record FileView(String path, String oldPath, String status, String content, String baseContent,
						   boolean binary, boolean truncated, long size) {
	}

	private final GitCommandRunner git;

	public ChangesService(GitCommandRunner git) {
		this.git = git;
	}

	public ChangeList list(Path worktree, String baseBranch) {
		if (!Files.isDirectory(worktree)) {
			return new ChangeList(baseBranch, null, false, List.of());
		}
		String mergeBase = mergeBase(worktree, baseBranch);
		boolean baseKnown = mergeBase != null;
		String from = baseKnown ? mergeBase : "HEAD";

		Map<String, Entry> entries = new LinkedHashMap<>();
		for (Entry e : parseNameStatus(run(worktree, "diff", "--name-status", "-z", "-M", from))) {
			entries.put(e.path, e);
		}
		for (String[] n : parseNumstat(run(worktree, "diff", "--numstat", "-z", "-M", from))) {
			Entry e = entries.get(n[2]);
			if (e != null) {
				e.additions = n[0].equals("-") ? 0 : Integer.parseInt(n[0]);
				e.deletions = n[1].equals("-") ? 0 : Integer.parseInt(n[1]);
			}
		}

		Set<String> committed = baseKnown
				? paths(parseNameStatus(run(worktree, "diff", "--name-status", "-z", "-M", from, "HEAD")))
				: Set.of();
		Set<String> staged = paths(parseNameStatus(run(worktree, "diff", "--cached", "--name-status", "-z", "-M", "HEAD")));
		Set<String> unstaged = paths(parseNameStatus(run(worktree, "diff", "--name-status", "-z")));
		for (Entry e : entries.values()) {
			if (committed.contains(e.path)) e.where.add("committed");
			if (staged.contains(e.path)) e.where.add("staged");
			if (unstaged.contains(e.path)) e.where.add("unstaged");
			if (e.where.isEmpty()) e.where.add("unstaged");
		}

		String untracked = run(worktree, "ls-files", "--others", "--exclude-standard", "-z");
		for (String path : untracked.split("\0")) {
			if (path.isEmpty() || entries.containsKey(path)) {
				continue;
			}
			Entry e = new Entry(path, null, "added");
			e.where.add("untracked");
			e.additions = countLines(worktree.resolve(path));
			entries.put(path, e);
		}

		List<ChangedFile> files = entries.values().stream()
				.map(e -> new ChangedFile(e.path, e.oldPath, e.status, List.copyOf(e.where), e.additions, e.deletions,
						mtime(worktree.resolve(e.path))))
				.sorted(Comparator.comparing(ChangedFile::path))
				.toList();
		return new ChangeList(baseBranch, mergeBase, baseKnown, files);
	}

	/**
	 * @throws IllegalArgumentException malformed path, or one that resolves outside the worktree / through a symlink (→ 400)
	 * @throws NoSuchElementException   the path isn't in the current change list (→ 404)
	 */
	public FileView file(Path worktree, String baseBranch, String path) {
		validatePath(path);
		ChangeList changes = list(worktree, baseBranch);
		ChangedFile row = changes.files().stream().filter(f -> f.path().equals(path)).findFirst()
				.orElseThrow(() -> new NoSuchElementException("not a changed file: " + path));

		Path target = safeResolve(worktree, path);
		boolean binary = false;
		boolean truncated = false;
		long size = 0;
		String content = null;
		if (!"deleted".equals(row.status())) {
			Raw raw = readWorking(target);
			size = raw.size;
			truncated = raw.truncated;
			binary = raw.binary;
			content = raw.text;
		}

		String baseContent = null;
		if (!"added".equals(row.status())) {
			String basePath = row.oldPath() != null ? row.oldPath() : row.path();
			String rev = (changes.baseKnown() ? changes.mergeBase() : "HEAD") + ":" + basePath;
			var shown = git.runBytes(worktree, MAX_FILE_BYTES, "show", rev);
			if (shown.ok()) {
				Raw raw = decode(shown.stdout(), shown.truncated(), shown.stdout().length);
				binary |= raw.binary;
				truncated |= raw.truncated;
				baseContent = raw.text;
			}
		}
		if (binary) {
			content = null;
			baseContent = null;
		}
		return new FileView(path, row.oldPath(), row.status(), content, baseContent, binary, truncated, size);
	}

	// --- path safety ---

	/** Syntactic checks, before anything touches the filesystem. */
	static void validatePath(String path) {
		if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("path is required");
		}
		if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
			throw new IllegalArgumentException("path must be relative to the worktree");
		}
		for (String segment : path.split("[/\\\\]", -1)) {
			if (segment.equals("..") || segment.equals(".")) {
				throw new IllegalArgumentException("path must not contain '.' or '..' segments");
			}
			if (segment.toLowerCase(Locale.ROOT).equals(".git")) {
				throw new IllegalArgumentException("path must not point into .git");
			}
		}
	}

	/**
	 * Resolves against the real worktree root: the real parent directory must stay inside it and the
	 * file itself must not be a symlink (a changed symlink is listed, but never followed).
	 */
	static Path safeResolve(Path worktree, String path) {
		try {
			Path root = worktree.toRealPath();
			Path target = root.resolve(path).normalize();
			if (!target.startsWith(root)) {
				throw new IllegalArgumentException("path escapes the worktree");
			}
			Path parent = target.getParent();
			if (parent != null && Files.exists(parent) && !parent.toRealPath().startsWith(root)) {
				throw new IllegalArgumentException("path escapes the worktree through a symlinked directory");
			}
			if (Files.isSymbolicLink(target)) {
				throw new IllegalArgumentException("path is a symlink; symlinks are not followed");
			}
			return target;
		} catch (IOException e) {
			throw new IllegalArgumentException("cannot resolve path: " + e.getMessage());
		}
	}

	// --- content ---

	private record Raw(String text, boolean binary, boolean truncated, long size) {
	}

	private Raw readWorking(Path target) {
		try (var in = Files.newInputStream(target)) {
			byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
			long size = bytes.length > MAX_FILE_BYTES ? Files.size(target) : bytes.length;
			boolean truncated = bytes.length > MAX_FILE_BYTES;
			return decode(truncated ? java.util.Arrays.copyOf(bytes, MAX_FILE_BYTES) : bytes, truncated, size);
		} catch (NoSuchFileException e) {
			return new Raw("", false, false, 0); // listed but gone since: show as empty rather than failing
		} catch (IOException e) {
			throw new IllegalArgumentException("cannot read file: " + e.getMessage());
		}
	}

	/** UTF-8 only: NUL bytes or invalid sequences mean binary. A cut-off tail of a truncated file is not an error. */
	private static Raw decode(byte[] bytes, boolean truncated, long size) {
		int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
		for (int i = 0; i < sniff; i++) {
			if (bytes[i] == 0) {
				return new Raw(null, true, truncated, size);
			}
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			CharBuffer out = CharBuffer.allocate(bytes.length + 1);
			var result = decoder.decode(ByteBuffer.wrap(bytes), out, !truncated);
			if (result.isError()) {
				result.throwException();
			}
			if (!truncated) {
				decoder.flush(out);
			}
			out.flip();
			return new Raw(out.toString(), false, truncated, size);
		} catch (CharacterCodingException e) {
			return new Raw(null, true, truncated, size);
		}
	}

	private static long mtime(Path file) {
		try {
			return Files.getLastModifiedTime(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private int countLines(Path file) {
		try {
			if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
				return 0;
			}
			try (var in = Files.newInputStream(file)) {
				byte[] bytes = in.readNBytes(MAX_FILE_BYTES);
				Raw raw = decode(bytes, false, bytes.length);
				if (raw.binary || raw.text.isEmpty()) {
					return 0;
				}
				int lines = (int) raw.text.chars().filter(c -> c == '\n').count();
				return raw.text.endsWith("\n") ? lines : lines + 1;
			}
		} catch (IOException e) {
			return 0;
		}
	}

	// --- git plumbing ---

	private String mergeBase(Path worktree, String baseBranch) {
		if (baseBranch == null || baseBranch.isBlank() || baseBranch.startsWith("-")) {
			return null;
		}
		var result = git.run(worktree, "merge-base", baseBranch, "HEAD");
		return result.ok() && !result.stdout().isBlank() ? result.stdout() : null;
	}

	private String run(Path worktree, String... args) {
		return git.runOrThrow(worktree, args).stdout();
	}

	private static final class Entry {
		final String path;
		final String oldPath;
		final String status;
		final Set<String> where = new LinkedHashSet<>();
		int additions;
		int deletions;

		Entry(String path, String oldPath, String status) {
			this.path = path;
			this.oldPath = oldPath;
			this.status = status;
		}
	}

	private static Set<String> paths(List<Entry> entries) {
		Set<String> out = new LinkedHashSet<>();
		entries.forEach(e -> out.add(e.path));
		return out;
	}

	/** {@code git diff --name-status -z}: {@code M\0path\0}, {@code R100\0old\0new\0}. */
	private static List<Entry> parseNameStatus(String out) {
		List<Entry> entries = new ArrayList<>();
		String[] t = out.split("\0");
		int i = 0;
		while (i < t.length) {
			String code = t[i++];
			if (code.isEmpty()) {
				continue;
			}
			char c = code.charAt(0);
			if (c == 'R' || c == 'C') {
				if (i + 1 >= t.length) break;
				String oldPath = t[i++];
				String newPath = t[i++];
				entries.add(c == 'R' ? new Entry(newPath, oldPath, "renamed") : new Entry(newPath, null, "added"));
			} else {
				if (i >= t.length) break;
				String path = t[i++];
				entries.add(new Entry(path, null, switch (c) {
					case 'A' -> "added";
					case 'D' -> "deleted";
					default -> "modified"; // M, T (type change), U (unmerged)
				}));
			}
		}
		return entries;
	}

	/** {@code git diff --numstat -z}: {@code adds\tdels\tpath\0}, or {@code adds\tdels\t\0old\0new\0} for a rename. Returns [adds, dels, path]. */
	private static List<String[]> parseNumstat(String out) {
		List<String[]> rows = new ArrayList<>();
		String[] t = out.split("\0", -1);
		int i = 0;
		while (i < t.length) {
			String head = t[i++];
			if (head.isEmpty()) {
				continue;
			}
			String[] parts = head.split("\t", 3);
			if (parts.length < 3) {
				continue;
			}
			if (parts[2].isEmpty()) { // rename: next two tokens are old and new path
				if (i + 1 >= t.length) break;
				i++; // old
				rows.add(new String[] {parts[0], parts[1], t[i++]});
			} else {
				rows.add(new String[] {parts[0], parts[1], parts[2]});
			}
		}
		return rows;
	}
}
