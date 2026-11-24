package com.contra.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MissingDatabaseConfigFailureAnalyzerTest {

	private final RuntimeException startupFailure = new RuntimeException("boom");

	@Test
	void namesTheMissingVariables() {
		MockEnvironment environment = new MockEnvironment().withProperty("DB_USERNAME", "contra");

		FailureAnalysis analysis = new MissingDatabaseConfigFailureAnalyzer(environment).analyze(startupFailure);

		assertThat(analysis.getDescription()).isEqualTo("Missing DB_URL, DB_PASSWORD: copy .env.example to .env");
	}

	@Test
	void staysOutOfTheWayWhenAllVariablesAreSet() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("DB_URL", "jdbc:postgresql://localhost:5432/contra")
				.withProperty("DB_USERNAME", "contra")
				.withProperty("DB_PASSWORD", "secret");

		assertThat(new MissingDatabaseConfigFailureAnalyzer(environment).analyze(startupFailure)).isNull();
	}
}
