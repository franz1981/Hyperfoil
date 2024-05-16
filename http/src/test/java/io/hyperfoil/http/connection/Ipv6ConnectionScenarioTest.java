package io.hyperfoil.http.connection;

import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.statistics.StatisticsSnapshot;
import io.hyperfoil.http.HttpScenarioTest;
import io.hyperfoil.http.statistics.HttpStats;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class Ipv6ConnectionScenarioTest extends HttpScenarioTest {

    @Override
    protected void initRouter() {
        router.route("/").handler(ctx -> ctx.response().end("Hello, world!"));
    }

    @Override
    protected String serverHost() {
        return "[::1]";
    }

    @Override
    protected int serverPort() {
        return 8080;
    }

    @Test
    public void singleRequestTest() {
        Benchmark benchmark = loadScenario("scenarios/Ipv6SingleRequest.hf.yaml");
        Map<String, StatisticsSnapshot> stats = runScenario(benchmark);
        validateStats(stats.get("test"));
    }

    private void validateStats(StatisticsSnapshot snapshot) {
        assertThat(snapshot.connectionErrors).isEqualTo(0);
        assertThat(snapshot.requestCount).isEqualTo(1);
        assertThat(HttpStats.get(snapshot).status_2xx).isEqualTo(1);
        assertThat(snapshot.invalid).isEqualTo(0);
    }
}
