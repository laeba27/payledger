package com.laeba.payledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Smoke test: the whole app boots against real Postgres + Redis,
 * Flyway applies migrations, and the health endpoint reports both as UP.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayledgerApplicationIT {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void healthIsUpWithDatabaseAndRedis() {
		String body = RestClient.create("http://localhost:" + port)
				.get().uri("/actuator/health")
				.retrieve()
				.body(String.class);

		assertThat(body)
				.contains("\"status\":\"UP\"")
				.contains("\"db\"")
				.contains("\"redis\"");
	}

	@Test
	void flywayAppliedBaselineMigration() {
		Integer applied = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
				Integer.class);

		assertThat(applied).isEqualTo(1);
	}

}
