package io.sailrocket;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.HdrHistogram.HistogramIterationValue;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.CommandRuntime;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.aesh.command.parser.CommandLineParserException;

import io.sailrocket.api.config.Benchmark;
import io.sailrocket.api.http.HttpMethod;
import io.sailrocket.api.statistics.LongValue;
import io.sailrocket.api.statistics.StatisticsSnapshot;
import io.sailrocket.core.builders.BenchmarkBuilder;
import io.sailrocket.core.builders.PhaseBuilder;
import io.sailrocket.core.builders.SimulationBuilder;
import io.sailrocket.core.extractors.ByteBufSizeRecorder;
import io.sailrocket.core.impl.LocalSimulationRunner;
import io.sailrocket.core.impl.statistics.StatisticsCollector;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class Wrk {
   private static final Logger log = LoggerFactory.getLogger(Wrk.class);

   public static void main(String[] args) throws CommandLineParserException {
      CommandRuntime runtime = AeshCommandRuntimeBuilder.builder()
            .commandRegistry(AeshCommandRegistryBuilder.builder()
                  .command(WrkCommand.class).create())
            .build();

      StringBuilder sb = new StringBuilder("wrk ");
      if (args.length == 1) {
         // When executed from mvn exec:exec -Pwrk -Dwrk.args="..." we don't want to quote the args
         sb.append(args[0]);
      } else {
         for (String arg : args) {
            if (arg.indexOf(' ') >= 0) {
               sb.append('"').append(arg).append("\" ");
            } else {
               sb.append(arg).append(' ');
            }
         }
      }
      try {
         runtime.executeCommand(sb.toString());
      } catch (Exception e) {
         log.error("Failed to execute command.", e);
         log.info("{}", runtime.commandInfo("wrk"));
      }
   }

   @CommandDefinition(name = "wrk", description = "API implemented by SailRocket")
   public class WrkCommand implements Command<CommandInvocation> {
      @Option(shortName = 'c', description = "Total number of HTTP connections to keep open", required = true)
      int connections;

      @Option(shortName = 'd', description = "Duration of the test, e.g. 2s, 2m, 2h", required = true)
      String duration;

      @Option(shortName = 't', description = "Total number of threads to use.")
      int threads;

      @Option(shortName = 'R', description = "Work rate (throughput)", required = true)
      int rate;

      @Option(shortName = 's', description = "!!!NOT SUPPORTED: LuaJIT script")
      String script;

      @OptionList(shortName = 'H', name = "header", description = "HTTP header to add to request, e.g. \"User-Agent: wrk\"")
      List<String> headers;

      @Option(description = "Print detailed latency statistics", hasValue = false)
      boolean latency;

      @Option(description = "Record a timeout if a response is not received within this amount of time.", defaultValue = "60s")
      String timeout;

      @Argument(description = "URL that should be accessed", required = true)
      String url;

      String path;
      String[][] parsedHeaders;

      @Override
      public CommandResult execute(CommandInvocation commandInvocation) throws CommandException, InterruptedException {
         if (script != null) {
            log.warn("Scripting is not supported at this moment.");
         }
         if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
         }
         URI uri;
         try {
            uri = new URI(url);
         } catch (URISyntaxException e) {
            log.error("Failed to parse URL: ", e);
            System.exit(1);
            return null;
         }
         String baseUrl = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >=0 ? ":" + uri.getPort() : "");
         path = uri.getPath();
         if (uri.getQuery() != null) {
            path = path + "?" + uri.getQuery();
         }
         if (uri.getFragment() != null) {
            path = path + "#" + uri.getFragment();
         }
         if (headers != null) {
            parsedHeaders = new String[headers.size()][];
            for (int i = 0; i < headers.size(); i++) {
               String h = headers.get(i);
               int colonIndex = h.indexOf(':');
               if (colonIndex < 0) {
                  log.warn("Cannot parse header '{}', ignoring.", h);
                  continue;
               }
               String header = h.substring(0, colonIndex).trim();
               String value = h.substring(colonIndex + 1).trim();
               parsedHeaders[i] = new String[] { header, value };
            }
         } else {
            parsedHeaders = null;
         }
         SimulationBuilder simulationBuilder = new BenchmarkBuilder(null)
               .name("wrk " + new SimpleDateFormat("YY/MM/dd HH:mm:ss").format(new Date()))
               .simulation()
                  .http()
                     .baseUrl(baseUrl)
                     .sharedConnections(connections)
                  .endHttp()
                  .threads(this.threads);

         addPhase(simulationBuilder, "calibration", "1s");
         addPhase(simulationBuilder, "test", duration).startAfter("calibration").maxDuration(duration);
         Benchmark benchmark = simulationBuilder.endSimulation().build();

         // TODO: allow running the benchmark from remote instance
         LocalSimulationRunner runner = new LocalSimulationRunner(benchmark);
         log.info("Running {} test @ {}", duration, url);
         log.info("  {} threads and {} connections", threads, connections);
         runner.run();
         StatisticsCollector collector = new StatisticsCollector(benchmark.simulation());
         runner.visitSessions(collector);
         collector.visitStatistics((phase, sequence, stats) -> {
            if ("test".equals(phase.name())) {
               printStats(stats);
            }
         });
         return null;
      }

      private PhaseBuilder addPhase(SimulationBuilder simulationBuilder, String phase, String duration) {
         return simulationBuilder.addPhase(phase).constantPerSec(rate)
                  .duration(duration)
                  .maxSessionsEstimate(rate * 15)
                  .scenario()
                     .initialSequence("request")
                        .step().httpRequest(HttpMethod.GET)
                           .path(path)
                           .headerAppender((session, request) -> {
                              if (parsedHeaders != null) {
                                 for (String[] header : parsedHeaders) {
                                    request.putHeader(header[0], header[1]);
                                 }
                              }
                           })
                           .timeout(timeout)
                           .handler()
                              .rawBytesHandler(new ByteBufSizeRecorder("bytes"))
                           .endHandler()
                        .endStep()
                        .step().awaitAllResponses()
                     .endSequence()
                  .endScenario();
      }

      private void printStats(StatisticsSnapshot stats) {
         long dataRead = ((LongValue) stats.custom.get("bytes")).value();
         double durationSeconds = (stats.histogram.getEndTimeStamp() - stats.histogram.getStartTimeStamp()) / 1000d;
         log.info("  {} requests in {}s, {} read", stats.histogram.getTotalCount(), durationSeconds, formatData(dataRead));
         log.info("                 Avg    Stdev      Max");
         log.info("Latency:      {} {} {}", formatTime(stats.histogram.getMean()), formatTime(stats.histogram.getStdDeviation()), formatTime(stats.histogram.getMaxValue()));
         if (latency) {
            log.info("Latency Distribution");
            for (double percentile : new double[] { 0.5, 0.75, 0.9, 0.99, 0.999, 0.9999, 0.99999, 1.0}) {
               log.info("{}% {}", String.format("%7.3f", 100 * percentile), formatTime(stats.histogram.getValueAtPercentile(100 * percentile)));
            }
            log.info("----------------------------------------------------------");
            log.info("Detailed Percentile Spectrum");
            log.info("   Value  Percentile  TotalCount  1/(1-Percentile)");
            for (HistogramIterationValue value : stats.histogram.percentiles(5)) {
               log.info("{}  {}", formatTime(value.getValueIteratedTo()), String.format("%9.5f%%  %10d  %15.2f",
                     value.getPercentile(), value.getTotalCountToThisValue(), 100/(100 - value.getPercentile())));
            }
            log.info("----------------------------------------------------------");
         }
         log.info("Requests/sec: {}", stats.histogram.getTotalCount() / durationSeconds);
         if (stats.errors() > 0) {
            log.info("Socket errors: connect {}, reset {}, timeout {}", stats.connectFailureCount, stats.resetCount, stats.timeouts);
            log.info("Non-2xx or 3xx responses: {}", stats.status_4xx + stats.status_5xx + stats.status_other);
         }
         log.info("Transfer/sec: {}", formatData(dataRead / durationSeconds));
      }

      private String formatData(double value) {
         double scaled;
         String suffix;
         if (value >= 1024 * 1024 * 1024) {
            scaled = (double) value / (1024 * 1024 * 1024);
            suffix = "GB";
         } else if (value >= 1024 * 1024) {
            scaled = (double) value / (1024 * 1024);
            suffix = "MB";
         }  else if (value >= 1024) {
            scaled = (double) value / 1024;
            suffix = "kB";
         } else {
            scaled = value;
            suffix = "B ";
         }
         return String.format("%6.2f%s", scaled, suffix);
      }

      private String formatTime(double value) {
         String suffix = "ns";
         if (value >= 1000_000_000) {
            value /= 1000_000_000;
            suffix = "s ";
         } else if (value >= 1000_000) {
            value /= 1000_000;
            suffix = "ms";
         } else if (value >= 1000) {
            value /= 1000;
            suffix = "us";
         }
         return String.format("%6.2f%s", value, suffix);
      }
   }
}
