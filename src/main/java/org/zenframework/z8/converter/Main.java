package org.zenframework.z8.converter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.core.util.IOUtils;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.*;
import static java.nio.charset.StandardCharsets.UTF_8;

@Command(name = "z8-converter", mixinStandardHelpOptions = true, version = "1.4.0")
public class Main implements Callable<Integer> {
  static final Pattern AMPERSAND = Pattern.compile("&");
  @Spec
  Model.CommandSpec spec;
  @Option(names = {"-p", "--port"}, description = "REST API port")
  private int port = 8080;
  @Option(names = {"-t", "--timeout"}, description = "Converter operation timeout", paramLabel = "sec")
  private int timeout = 20;

  public static void main(String[] args) throws IOException {
    int exitCode = new CommandLine(new Main()).execute(args);
    if (exitCode > 0) System.exit(exitCode);
  }

  @Command
  private void jod(@Parameters(index = "0", description = "LibreOffice distribution") Path distDir) throws OfficeException, IOException {
    OfficeManager om = LocalOfficeManager.builder().officeHome(distDir.toFile()).build();
    om.start();
    DocumentConverter dc = LocalConverter.make(om);

    HttpServer s = httpServer((e, input, output, from, to) -> {
      DocumentFormat f = dc.getFormatRegistry().getFormatByExtension(from);
      if (f == null) throw new RuntimeException("x2t: unsupported extension: " + from);
      DocumentFormat t = dc.getFormatRegistry().getFormatByExtension(to);
      if (t == null) throw new RuntimeException("x2t: unsupported extension: " + to);

      try {
        Path temp = Files.createTempFile("z8-converter-", "." + to);

        try (OutputStream o = Files.newOutputStream(temp)) {
          dc.convert(input).as(f).to(o).as(t).execute();
        }

        try (InputStream i = Files.newInputStream(temp)) {
          e.sendResponseHeaders(HTTP_OK, 0);
          IOUtils.copy(i, output);
        }
      } catch (OfficeException | IOException e2) {
        throw new RuntimeException(e2);
      }
    });
    System.err.println("starting jod HTTP server…");
    s.start();
  }

  @Command
  private void x2t(@Parameters(index = "0", description = "x2t distribution") Path distDir) throws IOException {
    X2t x2t = X2t.of(distDir, timeout);
    HttpServer s = httpServer((e, input, output, from, to) -> {
      if (!X2t.EXTENSIONS_WHITELIST.contains(from)) throw new RuntimeException("x2t: unsupported extension: " + from);
      if (!X2t.EXTENSIONS_WHITELIST.contains(to)) throw new RuntimeException("x2t: unsupported extension: " + to);

      try (InputStream result = x2t.run(input, from, to);) {
        e.sendResponseHeaders(HTTP_OK, 0);
        IOUtils.copy(result, output);
      } catch (IOException | InterruptedException | TransformerException e2) {
        throw new RuntimeException(e2);
      }
    });
    System.err.println("starting x2t HTTP server…");
    s.start();
  }

  @Command
  private void docbuilder() throws IOException {
    docbuilder.CDocBuilder.initialize("");
    docbuilder.CDocBuilder b = new docbuilder.CDocBuilder();
    HttpServer s = httpServer((e, input, output, from, to) -> {
      if (!X2t.EXTENSIONS_WHITELIST.contains(from)) throw new RuntimeException("x2t: unsupported extension: " + from);
      if (!X2t.EXTENSIONS_WHITELIST.contains(to)) throw new RuntimeException("x2t: unsupported extension: " + to);

      try {
        Path f = Files.createTempFile("z8-converter-", "." + from);
        Path t = Files.createTempFile("z8-converter-", "." + to);
        try (OutputStream o = Files.newOutputStream(f)) {
          IOUtils.copy(input, o);
        }

        b.openFile(f.toAbsolutePath().toString(), "");
        b.saveFile(to, t.toAbsolutePath().toString());

        try (InputStream i = Files.newInputStream(t)) {
          e.sendResponseHeaders(HTTP_OK, 0);
          IOUtils.copy(i, output);
        }
      } catch (IOException e2) {
        throw new RuntimeException(e2);
      }
    });
    System.err.println("starting docbuilder HTTP server…");
    s.start();
  }

  private HttpServer httpServer(Convert convert) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
    s.createContext("/api/v1/status", e -> {
      try (OutputStream o = e.getResponseBody()) {
        e.sendResponseHeaders(HTTP_OK, 0);
        o.write("OK".getBytes(UTF_8));
      } catch (Exception e2) {
        System.err.println("/api/v1/status: " + e2);
        e2.printStackTrace();
        e.sendResponseHeaders(HTTP_INTERNAL_ERROR, -1);
      }
    });
    s.createContext("/api/v1/convert", e -> {
      try (OutputStream o = e.getResponseBody()) {
        String from;
        String to;

        String q = e.getRequestURI().getQuery();
        if (!Objects.equals(e.getRequestMethod(), "POST")) {
          e.sendResponseHeaders(HTTP_BAD_METHOD, -1);
          return;
        } else if (q == null) {
          e.sendResponseHeaders(HTTP_BAD_REQUEST, -1);
          return;
        } else {
          Map<String, String> query = AMPERSAND.splitAsStream(q).collect(
            Collectors.toMap(
              it -> it.indexOf('=') == -1 ? it : it.substring(0, it.indexOf('=')),
              it -> it.indexOf('=') == -1 ? "" : it.substring(it.indexOf('=') + 1)
            )
          );
          from = query.get("from");
          to = query.get("to");
          if (from == null || to == null) {
            e.sendResponseHeaders(HTTP_BAD_REQUEST, -1);
            return;
          }
        }
        System.err.println("/api/v1/convert");
        convert.run(e, e.getRequestBody(), o, from, to);
      } catch (Exception e2) {
        System.err.println("/api/v1/convert: " + e2);
        e2.printStackTrace();
        e.sendResponseHeaders(HTTP_INTERNAL_ERROR, -1);
      }
    });
    return s;
  }

  @Override
  public Integer call() {
    throw new ParameterException(spec.commandLine(), "select mode: jod, x2t, docbuilder");
  }

  private interface Convert {
    void run(HttpExchange e, InputStream input, OutputStream o, String from, String to);
  }
}
