package com.contra.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application against a real PostgreSQL 16 in Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationStartupTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	@Autowired
	MockMvc mockMvc;

	@Test
	void connectsToPostgres16() {
		String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

		assertThat(version).startsWith("16.");
	}

	@Test
	void flywayAppliedAllMigrations() {
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.info().applied()).extracting(migration -> migration.getVersion().getVersion())
				.containsExactly("1", "2", "3", "4");
		assertThat(tableExists("accounts")).isTrue();
		assertThat(tableExists("ledger_entries")).isTrue();
		assertThat(tableExists("transfers")).isTrue();
	}

	@Test
	void unknownRouteReturnsProblemDetail() throws Exception {
		mockMvc.perform(get("/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.title").value("Not Found"));
	}

	@Test
	void openApiDocsAreServed() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").exists());
	}

	@Test
	void healthIsUpWithoutDetailsAndOtherActuatorEndpointsAreHidden() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
		mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
				Integer.class, tableName);
		return count != null && count == 1;
	}
}
