import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * QA (run_qa_windows.mjs FLINTQA_AOT): a copy of a jar without its signature, as the launcher makes of Mojang's client jar for
 * Java's start-up cache (app/src-tauri/src/jvmcache.rs unsigned_client_jar): the signature files go, the manifest keeps its
 * main section, every other entry is copied as it is. Usage: java Unsign.java IN OUT
 */
public class Unsign {
	public static void main(String[] args) throws IOException {
		Path out = Path.of(args[1]);
		Path part = Path.of(args[1] + ".part");
		try (ZipFile in = new ZipFile(args[0]); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
			for (var entries = in.entries(); entries.hasMoreElements(); ) {
				ZipEntry e = entries.nextElement();
				String upper = e.getName().toUpperCase();
				if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
					continue;
				}
				zip.putNextEntry(new ZipEntry(e.getName()));
				if (upper.equals("META-INF/MANIFEST.MF")) {
					Manifest m = new Manifest(in.getInputStream(e));
					m.getEntries().clear();
					m.write(zip);
				} else {
					in.getInputStream(e).transferTo(zip);
				}
				zip.closeEntry();
			}
		}
		Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
	}
}
