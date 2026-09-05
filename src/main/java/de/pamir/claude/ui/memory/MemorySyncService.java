package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.config.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Picks up human edits to the memory vault (LibrarySyncService's shape: ticks every minute,
 * the configured interval is applied as a last-run cutoff so Settings changes take effect on
 * the next tick without a restart). A changed file is re-indexed; a vanished file's doc is
 * archived (never deleted — the DB row is a rebuildable index, not the source of truth); a
 * file that reappears is restored via MemoryDocService.reindexFile's own restore-on-index logic.
 */
@Service
public class MemorySyncService {

	private static final Logger log = LoggerFactory.getLogger(MemorySyncService.class);

	private final SettingsService settings;
	private final MemoryPaths paths;
	private final MemoryDocService docService;
	private final MemoryRepository docs;
	private volatile Instant lastRun = Instant.EPOCH;

	public MemorySyncService(SettingsService settings, MemoryPaths paths, MemoryDocService docService,
							  MemoryRepository docs) {
		this.settings = settings;
		this.paths = paths;
		this.docService = docService;
		this.docs = docs;
	}

	@Scheduled(fixedDelay = 60_000)
	void tick() {
		Instant cutoff = Instant.now().minusSeconds(settings.memorySyncIntervalMinutes() * 60L);
		if (lastRun.isAfter(cutoff)) {
			return;
		}
		try {
			syncOnce();
		} catch (RuntimeException e) {
			log.warn("memory sync failed: {}", e.getMessage());
		} finally {
			lastRun = Instant.now();
		}
	}

	void syncOnce() {
		String root = settings.memoryRoot();
		if (!Files.exists(Path.of(root))) {
			return;
		}
		Set<String> seenRelPaths = new HashSet<>();
		syncEcosystem(root, seenRelPaths);
		syncServices(root, seenRelPaths);
		for (var doc : docs.findAll(null, null, "ACTIVE", null, null, null)) {
			if (!seenRelPaths.contains(doc.relPath())) {
				log.info("memory doc '{}' vanished from disk; archiving", doc.name());
				docService.archiveMissing(doc.id());
			}
		}
	}

	private void syncEcosystem(String root, Set<String> seenRelPaths) {
		Path dir = paths.ecosystemDir(root);
		if (!Files.exists(dir)) {
			return;
		}
		for (Path file : mdFiles(dir)) {
			seenRelPaths.add(paths.relPath(root, file));
			reindex(file, "ecosystem", null);
		}
	}

	private void syncServices(String root, Set<String> seenRelPaths) {
		Path servicesRoot = paths.servicesRoot(root);
		if (!Files.exists(servicesRoot)) {
			return;
		}
		try (Stream<Path> dirs = Files.list(servicesRoot)) {
			for (Path serviceDir : dirs.filter(Files::isDirectory).toList()) {
				Path marker = serviceDir.resolve(".repo-path");
				if (!Files.exists(marker)) {
					continue; // not a memory service dir (or not provisioned yet)
				}
				String servicePath = Files.readString(marker).strip();
				for (Path file : mdFiles(serviceDir)) {
					seenRelPaths.add(paths.relPath(root, file));
					reindex(file, "service", servicePath);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void reindex(Path file, String scope, String servicePath) {
		try {
			docService.reindexFile(file, scope, servicePath);
		} catch (RuntimeException e) {
			log.warn("failed to index memory doc {}: {}", file, e.getMessage());
		}
	}

	private static java.util.List<Path> mdFiles(Path dir) {
		try (Stream<Path> list = Files.list(dir)) {
			return list.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md")).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
