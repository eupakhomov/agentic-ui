package de.pamir.claude.ui.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** SPA fallback: any non-API, non-asset path renders the dashboard. */
@Controller
public class SpaForwardingController {

	@GetMapping({"/", "/{path:[^.]*}"})
	public String forward() {
		return "forward:/index.html";
	}
}
