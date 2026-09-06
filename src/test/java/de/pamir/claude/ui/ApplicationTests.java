package de.pamir.claude.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full context against a live Postgres — tagged "integration" so CI's
 * fast/DB-less unit-test run (see docs/plan/phase-9-production-hardening.md T1/T7)
 * excludes it via {@code -DexcludedGroups=integration}.
 */
@Tag("integration")
@SpringBootTest
class ApplicationTests {

	@Test
	void contextLoads() {
	}

}
