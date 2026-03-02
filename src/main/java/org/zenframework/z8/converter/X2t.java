package org.zenframework.z8.converter;

import org.jodconverter.core.util.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class X2t {
  public static final Set<String> EXTENSIONS_WHITELIST = new HashSet<>();
  static {
    EXTENSIONS_WHITELIST.add("pdf");
    EXTENSIONS_WHITELIST.add("docx");
    EXTENSIONS_WHITELIST.add("odt");
  }

  private static final String X2T_EXE = "x2t" + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "");
  private static final DocumentBuilder BUILDER;
  static {
    try {
      BUILDER = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }
  }
  private static final Transformer TRANSFORMER;
  static {
    try {
      TRANSFORMER = TransformerFactory.newInstance().newTransformer();
    } catch (TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }
  }

  private final Path distDir;
  private final int timeout;

  private X2t(Path distDir, int timeout) {
    this.distDir = distDir;
    this.timeout = timeout;
  }

  public static X2t of(Path distDir, int timeout) {
    distDir = distDir.toAbsolutePath();
    assert Files.exists(distDir.resolve(X2T_EXE));
    assert Files.exists(distDir.resolve("core-fonts"));
    assert Files.exists(distDir.resolve("AllFonts.js"));
    return new X2t(distDir, timeout);
  }

  public InputStream run(InputStream source, String from, String to) throws IOException, InterruptedException, TransformerException {
    Path workDir = Files.createTempDirectory("z8-converter-");
    Path x2tXml = workDir.resolve("x2t.xml");
    Path pathFrom = workDir.resolve("from." + from);
    Path pathTo = workDir.resolve("to." + to);
    Path tmpDir = workDir.resolve("tmp");

    try (OutputStream o = Files.newOutputStream(pathFrom)) {
      IOUtils.copy(source, o);
    }
    Files.createDirectory(tmpDir);

    Document d = BUILDER.newDocument();
    Element e1 = d.createElement("TaskQueueDataConvert");
    d.appendChild(e1);

    Element e2 = d.createElement("m_sFileFrom");
    e2.setTextContent(pathFrom.toString());
    e1.appendChild(e2);

    e2 = d.createElement("m_sFileTo");
    e2.setTextContent(pathTo.toString());
    e1.appendChild(e2);

    e2 = d.createElement("m_bDontSaveAdditional");
    e2.setTextContent("true");
    e1.appendChild(e2);

    e2 = d.createElement("m_sFontDir");
    e2.setTextContent(distDir.resolve("core-fonts").toString());
    e1.appendChild(e2);

    e2 = d.createElement("m_sAllFontsPath");
    e2.setTextContent(distDir.resolve("AllFonts.js").toString());
    e1.appendChild(e2);

    e2 = d.createElement("m_sTempDir");
    e2.setTextContent(tmpDir.toString());
    e1.appendChild(e2);

    StringWriter sw = new StringWriter();
    TRANSFORMER.transform(new DOMSource(d), new StreamResult(sw));

    Files.write(x2tXml, sw.toString().getBytes(UTF_8));
    Process p = new ProcessBuilder(distDir.resolve(X2T_EXE).toString(), x2tXml.toString())
      .directory(distDir.toFile())
      .start();
    p.waitFor(timeout, TimeUnit.SECONDS);
    if (p.exitValue() != 0)
      throw new IllegalStateException("x2t: exit code " + p.exitValue());

    return Files.newInputStream(pathTo);
  }
}
