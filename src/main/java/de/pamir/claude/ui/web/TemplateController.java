package de.pamir.claude.ui.web;

import tools.jackson.databind.JsonNode;
import de.pamir.claude.ui.session.TemplateRepository;
import de.pamir.claude.ui.session.TemplateRepository.TemplateEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

	public record TemplateRequest(String name, String description, JsonNode config,
								  List<UUID> skillAssetIds, List<UUID> agentAssetIds) {
	}

	private final TemplateRepository templates;

	public TemplateController(TemplateRepository templates) {
		this.templates = templates;
	}

	@GetMapping
	public List<TemplateEntity> list() {
		return templates.findAll();
	}

	@GetMapping("/{id}")
	public TemplateEntity get(@PathVariable UUID id) {
		return templates.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TemplateEntity create(@RequestBody TemplateRequest request) {
		validate(request);
		return templates.insert(request.name(), request.description(), request.config(),
				request.skillAssetIds(), request.agentAssetIds());
	}

	@PutMapping("/{id}")
	public TemplateEntity update(@PathVariable UUID id, @RequestBody TemplateRequest request) {
		validate(request);
		return templates.update(id, request.name(), request.description(), request.config(),
				request.skillAssetIds(), request.agentAssetIds());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		return templates.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private void validate(TemplateRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			throw new IllegalArgumentException("template name is required");
		}
		if (request.config() == null || !request.config().isObject()) {
			throw new IllegalArgumentException("template config must be a JSON object");
		}
	}
}
