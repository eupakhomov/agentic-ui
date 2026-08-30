package de.pamir.claude.ui.web;

import de.pamir.claude.ui.library.AssetScanService;
import de.pamir.claude.ui.library.AssetSourceRepository;
import de.pamir.claude.ui.library.LibraryAiService;
import de.pamir.claude.ui.library.LibraryRepository;
import de.pamir.claude.ui.library.LibraryService;
import de.pamir.claude.ui.library.LibrarySyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Skill & agent library: scan sources, import curated assets, search, manage synced sources. */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

	public record ScanRequest(String type, String ref) {
	}

	public record ImportRequest(SourceSpec source, List<LibraryService.ImportItem> items) {
	}

	public record SourceSpec(String type, String ref, boolean syncEnabled) {
	}

	public record AiFillRequest(String type, String ref, List<String> paths) {
	}

	public record AssetUpdate(String name, String description, List<String> tags, String status) {
	}

	public record SourceUpdate(Boolean syncEnabled) {
	}

	public record DismissRequest(List<String> paths) {
	}

	public record SourceView(UUID id, String type, String ref, boolean syncEnabled, Instant lastSyncedAt,
							  String lastSyncStatus, String lastSyncError, int assetCount,
							  List<DiscoveryView> discoveries) {
	}

	public record DiscoveryView(String path, String kind, Instant firstSeenAt) {
	}

	public record SearchHitView(LibraryRepository.AssetEntity asset, double distance) {
	}

	private final AssetScanService scanner;
	private final LibraryService library;
	private final LibraryAiService ai;
	private final LibrarySyncService sync;
	private final LibraryRepository assets;
	private final AssetSourceRepository sources;

	public LibraryController(AssetScanService scanner, LibraryService library, LibraryAiService ai,
							  LibrarySyncService sync, LibraryRepository assets, AssetSourceRepository sources) {
		this.scanner = scanner;
		this.library = library;
		this.ai = ai;
		this.sync = sync;
		this.assets = assets;
		this.sources = sources;
	}

	@PostMapping("/scan")
	public AssetScanService.ScanResult scan(@RequestBody ScanRequest request) {
		return scanner.scan(validateType(request.type()), requireRef(request.ref()));
	}

	@PostMapping("/import")
	public List<LibraryService.ImportItemResult> importItems(@RequestBody ImportRequest request) {
		if (request.source() == null) {
			throw new IllegalArgumentException("source is required");
		}
		return library.importItems(validateType(request.source().type()), requireRef(request.source().ref()),
				request.source().syncEnabled(), request.items());
	}

	@PostMapping("/ai-fill")
	public List<LibraryAiService.FilledMeta> aiFill(@RequestBody AiFillRequest request) {
		return ai.fill(validateType(request.type()), requireRef(request.ref()), request.paths());
	}

	@GetMapping("/assets")
	public List<LibraryRepository.AssetEntity> listAssets(@RequestParam(required = false) String kind,
														   @RequestParam(required = false) String status,
														   @RequestParam(required = false) String q,
														   @RequestParam(required = false) Integer limit,
														   @RequestParam(required = false) Integer offset) {
		return assets.findAll(kind, status, q, limit, offset);
	}

	@PatchMapping("/assets/{id}")
	public LibraryRepository.AssetEntity updateAsset(@PathVariable UUID id, @RequestBody AssetUpdate update) {
		return library.updateAsset(id, update.name(), update.description(), update.tags(), update.status());
	}

	@DeleteMapping("/assets/{id}")
	public ResponseEntity<Void> deleteAsset(@PathVariable UUID id) {
		library.deleteAsset(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/assets/{id}/content")
	public LibraryService.ContentPreview content(@PathVariable UUID id) {
		return library.previewContent(assets.get(id));
	}

	@GetMapping("/search")
	public List<SearchHitView> search(@RequestParam String q, @RequestParam(defaultValue = "10") int k,
									   @RequestParam(required = false) String kind) {
		if (q == null || q.isBlank()) {
			throw new IllegalArgumentException("q is required");
		}
		return library.search(q, Math.max(1, Math.min(100, k)), kind).stream()
				.map(hit -> new SearchHitView(hit.asset(), hit.distance())).toList();
	}

	@GetMapping("/sources")
	public List<SourceView> listSources() {
		return sources.findAll().stream().map(this::view).toList();
	}

	@PatchMapping("/sources/{id}")
	public SourceView updateSource(@PathVariable UUID id, @RequestBody SourceUpdate update) {
		if (update.syncEnabled() != null) {
			sources.setSyncEnabled(id, update.syncEnabled());
		}
		return view(sources.get(id));
	}

	@PostMapping("/sources/{id}/sync")
	public SourceView syncNow(@PathVariable UUID id) {
		sync.syncOne(id);
		return view(sources.get(id));
	}

	@DeleteMapping("/sources/{id}")
	public ResponseEntity<Void> deleteSource(@PathVariable UUID id) {
		return sources.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	@PostMapping("/sources/{id}/discoveries/dismiss")
	public SourceView dismiss(@PathVariable UUID id, @RequestBody(required = false) DismissRequest request) {
		sources.dismissDiscoveries(id, request == null ? null : request.paths());
		return view(sources.get(id));
	}

	private SourceView view(AssetSourceRepository.SourceEntity source) {
		List<DiscoveryView> discoveries = sources.findUndismissedDiscoveries(source.id()).stream()
				.map(d -> new DiscoveryView(d.sourcePath(), d.kind(), d.firstSeenAt())).toList();
		return new SourceView(source.id(), source.type(), source.ref(), source.syncEnabled(),
				source.lastSyncedAt(), source.lastSyncStatus(), source.lastSyncError(),
				assets.findBySource(source.id()).size(), discoveries);
	}

	private static String validateType(String type) {
		if (!Set.of("dir", "repo").contains(type)) {
			throw new IllegalArgumentException("type must be 'dir' or 'repo'");
		}
		return type;
	}

	private static String requireRef(String ref) {
		if (ref == null || ref.isBlank()) {
			throw new IllegalArgumentException("ref is required");
		}
		return ref.strip();
	}
}
