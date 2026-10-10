/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.test.jetty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/** Real jar relocation, including wrapped manifest values, JPMS and service loading. */
public final class RelocateJettyPackagesTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void put(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name)); jar.write(bytes); jar.closeEntry();
    }
    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("solr9-relocation-test-");
        try {
            Path input = root.resolve("jetty-fixture.jar"), output = root.resolve("lib/solr9-bridge-jetty-fixture.jar");
            String notice = "Original attribution, org.eclipse.jetty.orbit:javax.mail.glassfish\nCopyright upstream authors\n";
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Automatic-Module-Name", "org.eclipse.jetty.fixture");
            manifest.getMainAttributes().putValue("Import-Package", "org.eclipse.jetty.io;version=\"[10,11)\",org.eclipse.jetty.util,org.eclipse.jetty.alpn.client");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input), manifest)) {
                ClassWriter module = new ClassWriter(0);
                module.visit(Opcodes.V11, Opcodes.ACC_MODULE, "module-info", null, null, null);
                ModuleVisitor mv = module.visitModule("org.eclipse.jetty.fixture", 0, "10");
                mv.visitRequire("org.eclipse.jetty.io", 0, "10");
                mv.visitPackage("org/eclipse/jetty/fixture");
                mv.visitExport("org/eclipse/jetty/fixture", 0, "org.eclipse.jetty.client");
                mv.visitUse("org/eclipse/jetty/fixture/Service");
                mv.visitProvide("org/eclipse/jetty/fixture/Service", "org/eclipse/jetty/fixture/Provider");
                mv.visitEnd(); module.visitEnd();
                put(jar, "module-info.class", module.toByteArray());
                put(jar, "META-INF/versions/11/module-info.class", module.toByteArray());
                put(jar, "META-INF/services/org.eclipse.jetty.fixture.Service", text("org.eclipse.jetty.fixture.Provider\n"));
                put(jar, "META-INF/NOTICE.txt", text(notice));
                put(jar, "META-INF/LICENSE", text("Unchanged license\n"));
                put(jar, "META-INF/maven/org.eclipse.jetty/jetty-fixture/pom.properties", text("groupId=org.eclipse.jetty\n"));
                put(jar, "fixture.properties", text("factory=org.eclipse.jetty.fixture.Provider\n"));
                put(jar, "asset.bin", new byte[]{0, 1, (byte) 255});
                put(jar, "META-INF/old.SF", text("obsolete signature"));
            }
            RelocateJettyPackages.main(new String[]{input.toString(), output.toString()});
            try (JarFile jar = new JarFile(output.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                    String bytes = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                    require(!bytes.contains("org.eclipse.jetty") && !bytes.contains("org/eclipse/jetty"), "unrelocated linkage in " + entry.getName());
                }
                require("net.yacy.solr9.jetty.fixture".equals(jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name")), "module manifest name");
                require(jar.getManifest().getMainAttributes().getValue("Import-Package").contains("net.yacy.solr9.jetty.alpn.client"), "wrapped manifest import");
                require(jar.getEntry("META-INF/services/net.yacy.solr9.jetty.fixture.Service") != null, "service descriptor name");
                require(jar.getEntry("META-INF/old.SF") == null, "invalid signature removed");
                require(jar.getEntry("META-INF/maven/org.eclipse.jetty/jetty-fixture/pom.properties") == null, "original Maven identity is not runtime metadata");
                require(java.util.Arrays.equals(new byte[]{0, 1, (byte) 255}, jar.getInputStream(jar.getEntry("asset.bin")).readAllBytes()), "binary assets untouched");
                require(new String(jar.getInputStream(jar.getEntry("META-INF/LICENSE")).readAllBytes(), StandardCharsets.UTF_8).equals("Unchanged license\n"), "license untouched");
            }
            Path upstream = output.getParent().resolve("solr9-bridge-upstream/jetty-fixture");
            require(Files.readString(upstream.resolve("META-INF/NOTICE.txt")).equals(notice), "original attribution preserved verbatim");
            require(Files.readString(upstream.resolve("META-INF/maven/org.eclipse.jetty/jetty-fixture/pom.properties")).equals("groupId=org.eclipse.jetty\n"), "original coordinates preserved verbatim");
            require(Files.exists(upstream.resolve("META-INF/MANIFEST.MF")), "original manifest available");
            System.out.println("PASS: " + checks + " relocation packaging checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
