package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.ServiceReader;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.SnapshotReader;
import com.gimle.hugin.render.ClusterScreen;
import com.gimle.hugin.render.ColorMode;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.render.ServiceScreen;
import com.gimle.hugin.render.Viewport;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The terminal view, driven against a real cluster.
 *
 * <p>What is exercised here is the whole path an operator's {@code gimle top} takes short of the
 * terminal itself: the real control-plane routes, the real readers that parse them, and the real
 * screens that turn a snapshot into lines. Rendering is a pure function of (snapshot, ui state,
 * viewport, clock), so a scenario can assert on the frame as strings without a TTY, keystrokes, or
 * a pseudo-terminal. The one part no scenario reaches is the JLine adapter -- raw mode, key
 * decoding, resize -- which is why it is kept as thin as it is.
 */
public final class TerminalViewSteps {

  private static final Viewport VIEWPORT = new Viewport(160, 40);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final ScenarioWorld world;
  private List<String> frame = List.of();

  public TerminalViewSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When("the terminal view is rendered")
  public void theTerminalViewIsRendered() {
    ClusterSnapshot snapshot = new SnapshotReader(reader()).read();
    frame =
        new ClusterScreen(new Painter(ColorMode.NONE))
            .render(snapshot, new UiState(), VIEWPORT, false, Instant.now());
  }

  @When("the terminal view's services screen is rendered")
  public void theServicesScreenIsRendered() {
    ServiceSnapshot snapshot = new ServiceReader(reader()).read();
    frame =
        new ServiceScreen(new Painter(ColorMode.NONE))
            .render(snapshot, new UiState(), VIEWPORT, false, Instant.now());
  }

  @Then("the terminal view shows a line containing {string}")
  public void theViewShowsALineContaining(final String needle) {
    assertTrue(
        frame.stream().anyMatch(line -> line.contains(needle)),
        "no line containing '" + needle + "' in rendered frame:\n" + String.join("\n", frame));
  }

  @Then("the terminal view shows no line containing {string}")
  public void theViewShowsNoLineContaining(final String needle) {
    assertFalse(
        frame.stream().anyMatch(line -> line.contains(needle)),
        "unexpected line containing '"
            + needle
            + "' in rendered frame:\n"
            + String.join("\n", frame));
  }

  @Then("every terminal view line fits the terminal width")
  public void everyLineFitsTheWidth() {
    for (String line : frame) {
      assertTrue(
          line.length() <= VIEWPORT.columns(),
          "line wider than " + VIEWPORT.columns() + ": " + line);
    }
  }

  /**
   * The same narrowing read-only view the CLI hands a contributed verb, over this scenario's own
   * cluster. Written here rather than reused from {@code gimle-cli} so the scenario exercises the
   * real HTTP routes without also dragging in that module's client configuration.
   */
  private ClusterReader reader() {
    final String baseUrl = world.cluster().api().baseUrl();
    final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    return new ClusterReader() {

      @Override
      public List<Map<String, Object>> getList(final String path) {
        return Json.asObjectList(Json.parse(body(path)));
      }

      @Override
      public Map<String, Object> getObject(final String path) {
        return Json.asObject(Json.parse(body(path)));
      }

      @Override
      public InputStream openStream(final String path) {
        try {
          return httpClient.send(request(path), HttpResponse.BodyHandlers.ofInputStream()).body();
        } catch (IOException e) {
          throw new HolmgangException("could not open " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new HolmgangException("interrupted opening " + path, e);
        }
      }

      @Override
      public String serverAddress() {
        return baseUrl;
      }

      @Override
      public ClusterReader forContext(final String nameOrAddress) {
        throw new UnsupportedOperationException("this reader is not addressed by server");
      }

      private String body(final String path) {
        try {
          HttpResponse<String> response =
              httpClient.send(request(path), HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() / 100 != 2) {
            throw new HolmgangException(
                "GET " + path + " returned " + response.statusCode() + ": " + response.body());
          }
          return response.body();
        } catch (IOException e) {
          throw new HolmgangException("GET " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new HolmgangException("interrupted on GET " + path, e);
        }
      }

      private HttpRequest request(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT).GET().build();
      }
    };
  }
}
