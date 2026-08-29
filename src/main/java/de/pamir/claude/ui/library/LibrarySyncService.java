package de.pamir.claude.ui.library;

import de.pamir.claude.ui.config.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps synced sources fresh. Ticks every minute regardless of the configured interval
 * (which is applied as a last_synced_at cutoff, so Settings changes take effect on the
 * next tick — same shape as PrCheckPollingService). Per source: upstream change →
 * managed copy + hash refreshed (and re-embedded); upstream removal → asset ARCHIVED
 * (files kept); reappearance → restored; unimported upstream files → discovery rows
 * that drive the dashboard badge.
 */
@Service
public class LibrarySyncService {

	private static final Logger log = LoggerFactory.getLogger(LibrarySyncService.class);

	private final SettingsService settings;
	private final AssetScanService scanner;
	private final LibraryService library;
	private final LibraryRepository assets;
	private final AssetSourceRepository sources;

	public LibrarySyncService(SettingsService settings, AssetScanService scanner, LibraryService library,
							   LibraryRepository assets, AssetSourceRepository sources) {
		this.settings = settings;
		this.scanner = scanner;
		this.library = library;
		this.assets = assets;
		this.sources = sources;
	}

	@Scheduled(fixedDelay = 60_000)
	void syncTick() {
		if (!settings.librarySyncEnabled()) {
			return;
		}
		Instant cutoff = Instant.now().minusSeconds(settings.librarySyncIntervalMinutes() * 60L);
		for (var source : sources.findSyncDue(cutoff)) {
			syncOne(source.id());
		}
	}

	public void syncOne(UUID sourceId) {
		var source = sources.get(sourceId);
		try {
			var scan = scanner.scan(source.type(), source.ref());
			Path root = scanner.resolveRoot(source.type(), source.ref());
			Map<String, AssetScanService.Candidate> byPath = scan.candidates().stream()
					.collect(Collectors.toMap(AssetScanService.Candidate::path, Function.identity(), (a, b) -> a));
			reconcileAssets(source.id(), root, byPath);
			reconcileDiscoveries(source.id(), byPath);
			sources.recordSync(source.id(), "OK", null);
		} catch (RuntimeException e) {
			log.warn("library sync failed for {}: {}", source.ref(), e.getMessage());
			sources.recordSync(source.id(), "ERROR", e.getMessage());
		}
	}

	private void reconcileAssets(UUID sourceId, Path root, Map<String, AssetScanService.Candidate> byPath) {
		for (var asset : assets.findBySource(sourceId)) {
			if (asset.sourcePath() == null) {
				continue;
			}
			var candidate = byPath.get(asset.sourcePath());
			if (candidate == null) {
				if ("ACTIVE".equals(asset.status())) {
					log.info("library asset '{}' vanished upstream; archiving", asset.name());
					assets.updateStatus(asset.id(), "ARCHIVED");
				}
				continue;
			}
			if (!candidate.hash().equals(asset.contentHash())) {
				log.info("library asset '{}' changed upstream; refreshing copy", asset.name());
				Path upstream = ".".equals(asset.sourcePath()) ? root : root.resolve(asset.sourcePath());
				library.refreshAsset(asset, upstream, candidate.hash());
			}
			if ("ARCHIVED".equals(asset.status())) {
				log.info("library asset '{}' reappeared upstream; restoring", asset.name());
				assets.updateStatus(asset.id(), "ACTIVE");
			}
		}
	}

	private void reconcileDiscoveries(UUID sourceId, Map<String, AssetScanService.Candidate> byPath) {
		List<String> unimported = byPath.values().stream()
				.filter(c -> !c.alreadyImported())
				.map(AssetScanService.Candidate::path)
				.toList();
		sources.pruneDiscoveriesNotIn(sourceId, unimported);
		for (String path : unimported) {
			sources.upsertDiscovery(sourceId, path, byPath.get(path).kind());
		}
	}
}
