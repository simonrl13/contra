package com.contra.api.common;

import java.util.List;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Runs only when startup has already failed. If a DB_* variable is missing, that's almost certainly
 * the cause, so Boot prints this short explanation instead of a stack trace.
 *
 * Registered in META-INF/spring.factories. Boot passes the Environment through the constructor.
 */
public class MissingDatabaseConfigFailureAnalyzer implements FailureAnalyzer, Ordered {

	static final List<String> REQUIRED_VARIABLES = List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD");

	private final Environment environment;

	public MissingDatabaseConfigFailureAnalyzer(Environment environment) {
		this.environment = environment;
	}

	@Override
	public FailureAnalysis analyze(Throwable failure) {
		List<String> missing = REQUIRED_VARIABLES.stream()
				.filter(name -> !environment.containsProperty(name))
				.toList();
		if (missing.isEmpty()) {
			return null; // not our problem: let Boot's other analyzers explain it
		}
		return new FailureAnalysis(
				"Missing " + String.join(", ", missing) + ": copy .env.example to .env",
				"Create .env from .env.example, or set the variables in the environment.",
				failure);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE; // checked before Boot's generic DataSource analyzer
	}
}
