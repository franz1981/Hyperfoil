package io.hyperfoil.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.hyperfoil.api.session.ObjectAccess;
import io.hyperfoil.api.session.ReadAccess;
import io.hyperfoil.core.data.DataFormat;
import io.hyperfoil.core.steps.JsonStep;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class JsonStepTest extends BaseScenarioTest {

    @Test
    public void queryTest() {
        String jsonString = "{\n" +
                "  \"hero\": {\n" +
                "    \"name\": \"Fallback hero\",\n" +
                "    \"level\": 1,\n" +
                "    \"picture\": \"https://dummyimage.com/280x380/1e8fff/ffffff&text=Fallback+Hero\",\n" +
                "    \"powers\": \"Fallback hero powers\"\n" +
                "  },\n" +
                "  \"villain\": {\n" +
                "    \"name\": \"Fallback villain\",\n" +
                "    \"level\": 45,\n" +
                "    \"picture\": \"https://dummyimage.com/280x380/b22222/ffffff&text=Fallback+Villain\",\n" +
                "    \"powers\": \"Fallback villain powers\"\n" +
                "  }\n" +
                "}";
        scenario()
                .initialSequence("test")
                .step(() -> {
                    ObjectAccess json = SessionFactory.objectAccess("json");
                    return s1 -> {
                        System.out.println(jsonString);
                        json.setObject(s1, jsonString.getBytes(StandardCharsets.UTF_8));
                        return true;
                    };
                })
                .stepBuilder(new JsonStep.Builder()
                        .fromVar("json")
                        .query(".")
                        .storeShortcuts().format(DataFormat.STRING).toVar("output").end())
                .step(() -> {
                    ReadAccess output = SessionFactory.readAccess("output");
                    return s -> {
                        System.out.println(output.getObject(s));
                        return true;
                    };
                });
        runScenario();
    }

   @Test
   public void test() {
      scenario()
            .initialSequence("test")
            .step(() -> {
               ObjectAccess json = SessionFactory.objectAccess("json");
               return s1 -> {
                  json.setObject(s1, "{ \"foo\" : \"bar\\nbar\" }".getBytes(StandardCharsets.UTF_8));
                  return true;
               };
            })
            .stepBuilder(new JsonStep.Builder()
                  .fromVar("json")
                  .query(".foo")
                  .storeShortcuts().format(DataFormat.STRING).toVar("output").end())
            .step(() -> {
               ReadAccess output = SessionFactory.readAccess("output");
               return s -> {
                  assertThat(output.getObject(s)).isEqualTo("bar\nbar");
                  return true;
               };
            });
      runScenario();
   }
}
