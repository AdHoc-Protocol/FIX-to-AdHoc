package org.unirail;

import org.unirail.adhoc.AdHocWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.I4;
import static org.unirail.adhoc.AdHocWriter.brush;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;
import static org.unirail.adhoc.AdHocWriter.unique;

/**
 * FIX schema → AdHoc protocol description converter. Two XML inputs are recognised by their root element:
 * <ul>
 *   <li><b>SBE</b> message schemas ({@code <sbe:messageSchema>}, https://github.com/FIXTradingCommunity/fix-simple-binary-encoding);</li>
 *   <li><b>QuickFIX</b> data dictionaries ({@code <fix>}, https://github.com/quickfix/quickfix/tree/master/spec).</li>
 * </ul>
 * Usage: {@code java -cp out org.unirail.FIX2AdHoc <file or folder> [output folder]} — one {@code .cs} per input.
 */
public class FIX2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.FIX2AdHoc <SBE schema / QuickFIX dictionary .xml, or a folder> [output folder]");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		List<Path> inputs = new ArrayList<>();
		if (Files.isDirectory(src)) {
			File[] files = src.toFile().listFiles((d, n) -> n.endsWith(".xml"));
			if (files != null) {
				Arrays.sort(files);
				for (File f : files) inputs.add(f.toPath());
			}
		} else inputs.add(src);
		if (inputs.isEmpty()) {
			System.err.println("No .xml files in " + src);
			System.exit(1);
		}
		Files.createDirectories(dst);
		int failed = 0;
		for (Path in : inputs)
			try {
				Element root = parse(in).getDocumentElement();
				String kind = local(root);
				String cs;
				switch (kind) {
					case "messageSchema": cs = new Sbe(in, root).convert(); break;
					case "fix": cs = new QuickFix(in, root).convert(); break;
					default:
						System.out.printf("%-30s skipped (root <%s> is neither an SBE schema nor a QuickFIX dictionary)%n", in.getFileName(), kind);
						continue;
				}
				Path out = dst.resolve(projectName(in) + ".cs");
				Files.write(out, cs.getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-30s -> %s%n", in.getFileName(), out);
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + in + ": " + e);
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	static String projectName(Path file) {
		String n = file.getFileName().toString();
		if (n.endsWith(".xml")) n = n.substring(0, n.length() - 4);
		return ident(n);
	}

	/**
	 * Raises AdHoc's 255-item default for every collection. FIX payloads (option chains, market-data entry lists,
	 * security lists) routinely exceed 255 items, and an over-long value is a protocol error rather than a
	 * truncation, so the permissive default is the safer starting point; tighten it per field with {@code [D(+N)]}.
	 */
	static void defaultMaxLength(StringBuilder sb) {
		sb.append(I2).append("// Permissive defaults for FIX-sourced data — override per field with [D(+N)] / [D(N)].\n");
		sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
		sb.append(I3).append("Arrays  = 65_535,\n");
		sb.append(I3).append("Maps    = 65_535,\n");
		sb.append(I3).append("Sets    = 65_535,\n");
		sb.append(I3).append("Strings = 65_535,\n");
		sb.append(I2).append("}\n\n");
	}

	// ═══════════════════════════════════════════ DOM helpers ═══════════════════════════════════════════

	static Document parse(Path file) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(false);
		return f.newDocumentBuilder().parse(file.toFile());
	}

	/** Tag name without a namespace prefix ({@code sbe:message} → {@code message}). */
	static String local(Element e) {
		String n = e.getTagName();
		int c = n.indexOf(':');
		return c < 0 ? n : n.substring(c + 1);
	}

	static List<Element> children(Element e) {
		List<Element> list = new ArrayList<>();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element) list.add((Element) nl.item(i));
		return list;
	}

	static List<Element> children(Element e, String localName) {
		List<Element> list = new ArrayList<>();
		for (Element c : children(e)) if (local(c).equals(localName)) list.add(c);
		return list;
	}

	static String attr(Element e, String name) { return e.hasAttribute(name) ? e.getAttribute(name) : null; }

	static String attr(Element e, String name, String dflt) { return e.hasAttribute(name) ? e.getAttribute(name) : dflt; }

	static String text(Element e) {
		StringBuilder sb = new StringBuilder();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node n = nl.item(i);
			if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) sb.append(n.getNodeValue());
		}
		return sb.toString().trim();
	}

	static String charLiteral(String s) {
		char c = s.charAt(0);
		return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
	}

	static boolean isInteger(String s) {
		try { Long.parseLong(s.trim()); return true; } catch (NumberFormatException e) { return false; }
	}

	// ═══════════════════════════════ varint candidates (physics hints) ═══════════════════════════════

	/** Varint applies only to integers wider than one byte; the generator rejects it anywhere else. */
	static boolean varintCapable(String csType) {
		switch (csType) {
			case "short": case "ushort": case "int": case "uint": case "long": case "ulong": return true;
			default: return false;
		}
	}

	/**
	 * Neither FIX dialect states where a number's values actually sit, but a field's name, its description or a
	 * one-sided bound often implies it. Where such a hint exists this returns a comment naming the candidate
	 * attribute and the reason, emitted on the field itself: choosing a varint needs knowledge of the venue's
	 * traffic that a converter does not have, and the question must not be dropped silently.
	 *
	 * @param minOnly the declared minimum when the source gives a floor and no ceiling, otherwise null
	 */
	static String physicsHint(String name, String description, String csType, String minOnly) {
		if (!varintCapable(csType)) return null;
		if (minOnly != null)
			return "physics: floor " + minOnly + " declared with no ceiling → consider [A(" + minOnly + ")] if the values cluster near the floor";
		String d = description == null ? "" : description.toLowerCase();
		if (name.startsWith("Num") || (name.startsWith("No") && 2 < name.length() && Character.isUpperCase(name.charAt(2)))
				|| d.startsWith("number of") || d.contains("count of") || d.contains("number of repeating"))
			return "physics: a count — floored at 0 and unbounded above → consider [A]";
		if (name.endsWith("Qty") || name.endsWith("Quantity") || name.endsWith("Size") || name.endsWith("Volume"))
			return "physics: a quantity — non-negative and clustered low → consider [A]";
		if (name.endsWith("Px") || name.endsWith("Price"))
			return "physics: a price — the values cluster around the instrument's level, not around zero → consider [X(amplitude, level)] once that level is known";
		return null;
	}

	// ═══════════════════════════════════════════ SBE ═══════════════════════════════════════════

	/** Simple Binary Encoding schema → one AdHoc project. */
	static final class Sbe {
		final Path file;
		final Element root;
		final String project;
		/** Every named declaration of the {@code <types>} blocks (type / composite / enum / set) by SBE name. */
		final Map<String, Element> registry = new LinkedHashMap<>();
		/** Emitted AdHoc name of a declaration element (composites, enums, sets, typedefs). */
		final Map<Element, String> emittedName = new java.util.IdentityHashMap<>();
		final Set<String> projectNames = new HashSet<>();
		final StringBuilder decls = new StringBuilder();      // typedefs, enums, sets, composites (hoisted nested ones included)
		final StringBuilder messages = new StringBuilder();
		final Map<String, Integer> dashboard = new LinkedHashMap<>();
		final List<String> messagePacks = new ArrayList<>();
		boolean cappedLength;

		Sbe(Path file, Element root) {
			this.file = file;
			this.root = root;
			this.project = projectName(file);
			projectNames.add(project);
			projectNames.add("Initiator");
			projectNames.add("Acceptor");
			projectNames.add("Session");
		}

		String convert() throws Exception {
			List<Element> typeBlocks = new ArrayList<>();
			List<Element> msgs = new ArrayList<>();
			List<String> sources = new ArrayList<>();
			sources.add(file.getFileName().toString());
			for (Element c : children(root))
				switch (local(c)) {
					case "include": {
						Path inc = file.resolveSibling(attr(c, "href"));
						if (!Files.exists(inc)) {
							System.err.println("WARNING " + file.getFileName() + ": XInclude `" + inc + "` not found, skipped");
							break;
						}
						sources.add(inc.getFileName().toString());
						Element ir = parse(inc).getDocumentElement();
						if (local(ir).equals("types")) typeBlocks.add(ir);
						else typeBlocks.addAll(children(ir, "types"));
						break;
					}
					case "types": typeBlocks.add(c); break;
					case "message": msgs.add(c); break;
					default: break;
				}
			for (Element block : typeBlocks)
				for (Element d : children(block)) {
					String n = attr(d, "name");
					if (n != null && registry.putIfAbsent(n, d) != null)
						System.err.println("WARNING " + file.getFileName() + ": duplicate type name `" + n + "`, first definition kept");
				}
			// Reserve message names first: they are the packs users look for.
			Map<Element, String> msgNames = new java.util.IdentityHashMap<>();
			for (Element m : msgs) msgNames.put(m, unique(attr(m, "name"), projectNames));
			// Declarations in schema order.
			for (Element d : registry.values()) declare(d, null);
			for (Element m : msgs) message(m, msgNames.get(m));

			StringBuilder sb = new StringBuilder(1 << 16);
			AdHocWriter.fileHeader(sb, "FIX2AdHoc (SBE message schema)", String.join(", ", sources),
					"SBE package: " + attr(root, "package", "") + ", schema id: " + attr(root, "id", "") + ", version: " + attr(root, "version", "")
					+ ", semanticVersion: " + attr(root, "semanticVersion", "") + ", byteOrder: " + attr(root, "byteOrder", ""),
					"Schema description: " + attr(root, "description", ""));
			sb.append("namespace org.fix {\n");
			AdHocWriter.dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(project).append(" {\n\n");
			defaultMaxLength(sb);
			sb.append(I2).append("// ═════════════════════════ messages ═════════════════════════\n");
			sb.append(messages);
			sb.append('\n').append(I2).append("// ═════════════════════════ types (typedefs, enums, sets, composites) ═════════════════════════\n");
			sb.append(decls);
			sb.append('\n').append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			AdHocWriter.host(sb, I2, "Initiator", "The side that opens the session.");
			AdHocWriter.host(sb, I2, "Acceptor", "The side that accepts the session.");
			AdHocWriter.connectionOpen(sb, I2, "Session", "Initiator", "Acceptor");
			sb.append(I3).append("// Every SBE message, either direction; composites and typedefs stay sub-packs.\n");
			AdHocWriter.statePacks(sb, I3, "_____lr_____", "Established", messagePacks);
			sb.append(I2).append("}\n\n");
			attributes(sb);
			sb.append(I1).append("}\n}\n");
			return sb.toString();
		}

		void attributes(StringBuilder sb) {
			sb.append(I2).append("// ═════════════════════════ SBE metadata attributes ═════════════════════════\n\n");
			AdHocWriter.attribute(sb, I2, "Tag", "SBE field id (the FIX tag number).", "int id");
			AdHocWriter.attribute(sb, I2, "SinceVersion", "Schema version that introduced the element.", "int version");
			AdHocWriter.attribute(sb, I2, "Deprecated", "Schema version that deprecated the element.", "int version");
			AdHocWriter.attribute(sb, I2, "NullValue", "Wire value that means 'not set' for an optional encoding.", "string value");
			AdHocWriter.attribute(sb, I2, "CharacterEncoding", "Character encoding of a char / var-data encoding.", "string encoding");
			AdHocWriter.attribute(sb, I2, "SemanticType", "FIX semantic data type of the encoding (Qty, Price, UTCTimestamp, ...).", "string type");
			AdHocWriter.attribute(sb, I2, "DimensionType", "Composite that frames the repeating group on the wire.", "string composite");
			AdHocWriter.attribute(sb, I2, "BlockLength", "Fixed block length of the message / group root block on the SBE wire.", "long bytes");
			if (cappedLength)
				AdHocWriter.attribute(sb, I2, "DeclaredMaxLength", "The schema allows this many items; the AdHoc cap was reduced to " + MAX_VAR_LENGTH + ".", "long items");
		}

		static final long MAX_VAR_LENGTH = 1 << 20;

		// ───────────────────────────── declarations ─────────────────────────────

		/**
		 * Emits the AdHoc declaration of a {@code <types>} member (or of a declaration nested in a composite, then
		 * {@code owner} names the composite so a colliding name can be prefixed) and returns its AdHoc type name;
		 * plain {@code <type>}s without constraints return null (they resolve inline to a primitive).
		 */
		String declare(Element d, String owner) {
			String done = emittedName.get(d);
			if (done != null) return done;
			String name = attr(d, "name");
			switch (local(d)) {
				case "composite": return composite(d, chooseName(name, owner));
				case "enum": return enumeration(d, chooseName(name, owner));
				case "set": return set(d, chooseName(name, owner));
				case "type": return typedef(d, owner);
				default: return null;
			}
		}

		String chooseName(String name, String owner) {
			String n = ident(name);
			if (projectNames.contains(n) && owner != null) n = ident(owner + "_" + name);
			return unique(n, projectNames);
		}

		/** {@code <type>} with constraints → TYPEDEF class; a plain alias → null (inlined). Constants are inlined by the user. */
		String typedef(Element t, String owner) {
			if ("constant".equals(attr(t, "presence"))) return null;
			Prim p = prim(t);
			if (!p.constrained) return null;
			String name = chooseName(attr(t, "name"), owner);
			emittedName.put(t, name);
			StringBuilder sb = new StringBuilder();
			sb.append('\n');
			doc(sb, I2, attr(t, "description"));
			sb.append(I2).append("class ").append(name).append(" { ").append(p.field("TYPEDEF")).append(" }\n");
			decls.append(sb);
			return name;
		}

		String composite(Element c, String name) {
			emittedName.put(c, name);
			StringBuilder body = new StringBuilder();
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(name);
			for (Element m : children(c)) {
				String mname = attr(m, "name");
				switch (local(m)) {
					case "type": {
						String fn = unique(mname, fieldNames);
						doc(body, I3, attr(m, "description"));
						if ("constant".equals(attr(m, "presence"))) body.append(I3).append(constant(m, fn, text(m))).append('\n');
						else body.append(I3).append(attrsOf(m, prim(m).field(fn))).append('\n');
						break;
					}
					case "ref": {
						String fn = unique(mname, fieldNames);
						Element target = registry.get(attr(m, "type"));
						doc(body, I3, attr(m, "description"));
						if (target == null) {
							System.err.println("WARNING " + file.getFileName() + ": composite " + name + " refers to unknown type `" + attr(m, "type") + "`");
							body.append(I3).append("byte ").append(fn).append("; // unknown type ").append(attr(m, "type")).append('\n');
						} else body.append(I3).append(attrsOf(m, typedField(target, fn, false))).append('\n');
						break;
					}
					case "enum":
					case "set":
					case "composite": {
						// Declared in place in SBE; hoisted to project level here (a field and a nested type cannot share a name).
						String tn = declare(m, name);
						String fn = unique(mname, fieldNames);
						doc(body, I3, attr(m, "description"));
						body.append(I3).append(tn).append(' ').append(fn).append(";\n");
						break;
					}
					default: break;
				}
			}
			StringBuilder sb = new StringBuilder();
			sb.append('\n');
			doc(sb, I2, attr(c, "description"));
			sb.append(I2).append(attrsOf(c, "class " + name + " {")).append('\n');
			sb.append(body);
			sb.append(I2).append("}\n");
			decls.append(sb);
			return name;
		}

		String enumeration(Element e, String name) {
			emittedName.put(e, name);
			Prim enc = encoding(attr(e, "encodingType"));
			List<Element> values = children(e, "validValue");
			StringBuilder sb = new StringBuilder();
			sb.append('\n');
			doc(sb, I2, attr(e, "description"));
			if (values.size() < 2) {
				sb.append(I2).append("// SBE enum with fewer than two values: AdHoc rejects such enums, kept as a constants container.\n");
				sb.append(I2).append("public struct ").append(name).append(" {\n");
				Set<String> names = new HashSet<>();
				for (Element v : values) {
					doc(sb, I3, attr(v, "description"));
					sb.append(I3).append("public const ").append(enc.cs).append(' ').append(unique(attr(v, "name"), names)).append(" = ").append(enumLiteral(enc, text(v))).append(";\n");
				}
				if (values.isEmpty()) sb.append(I3).append("public const bool EMPTY = true;\n");
				sb.append(I2).append("}\n");
			} else {
				boolean wide = false;
				for (Element v : values) if (!enc.isChar && isInteger(text(v))) { long x = Long.parseLong(text(v).trim()); if (x < Integer.MIN_VALUE || Integer.MAX_VALUE < x) wide = true; }
				sb.append(I2).append("enum ").append(name).append(wide ? " : long" : "").append(" {\n");
				Set<String> names = new HashSet<>();
				for (Element v : values) {
					doc(sb, I3, attr(v, "description"));
					sb.append(I3).append(unique(attr(v, "name"), names)).append(" = ").append(enumLiteral(enc, text(v))).append(",\n");
				}
				sb.append(I2).append("}\n");
			}
			decls.append(sb);
			return name;
		}

		static String enumLiteral(Prim enc, String value) {
			if (enc.isChar) return charLiteral(value.isEmpty() ? "\0" : value);
			return isInteger(value) ? value.trim() : "0 /* " + value + " */";
		}

		String set(Element s, String name) {
			emittedName.put(s, name);
			List<Element> choices = children(s, "choice");
			StringBuilder sb = new StringBuilder();
			sb.append('\n');
			doc(sb, I2, attr(s, "description"));
			int maxBit = 0;
			for (Element c : choices) if (isInteger(text(c))) maxBit = Math.max(maxBit, Integer.parseInt(text(c).trim()));
			String under = maxBit >= 63 ? " : ulong" : maxBit >= 31 ? " : long" : "";
			if (choices.size() < 2) {
				sb.append(I2).append("// SBE set with fewer than two choices: AdHoc rejects such enums, kept as a constants container.\n");
				sb.append(I2).append("public struct ").append(name).append(" {\n");
				for (Element c : choices)
					sb.append(I3).append("public const ulong ").append(ident(attr(c, "name"))).append(" = ").append(bitLiteral(text(c), true)).append(";\n");
				if (choices.isEmpty()) sb.append(I3).append("public const bool EMPTY = true;\n");
				sb.append(I2).append("}\n");
			} else {
				sb.append(I2).append("[Flags]\n");
				sb.append(I2).append("enum ").append(name).append(under).append(" {\n");
				Set<String> names = new HashSet<>();
				for (Element c : choices) {
					doc(sb, I3, attr(c, "description"));
					sb.append(I3).append(unique(attr(c, "name"), names)).append(" = ").append(bitLiteral(text(c), maxBit >= 63)).append(",\n");
				}
				sb.append(I2).append("}\n");
			}
			decls.append(sb);
			return name;
		}

		static String bitLiteral(String bit, boolean unsigned) {
			if (!isInteger(bit)) return "0 /* " + bit + " */";
			int b = Integer.parseInt(bit.trim());
			return b == 63 ? "0x8000000000000000" : Long.toString(1L << b);
		}

		// ───────────────────────────── messages ─────────────────────────────

		void message(Element m, String name) {
			StringBuilder sb = new StringBuilder();
			sb.append('\n');
			doc(sb, I2, attr(m, "description"));
			sb.append(I2).append(attrsOf(m, "class " + name + " {")).append('\n');
			// The SBE template id identifies the message in the SBE wire format, which AdHoc replaces with its own.
			// It is kept as metadata so a migration can be audited; the AdHoc pack id is the agent's to assign.
			long id = isInteger(attr(m, "id", "")) ? Long.parseLong(attr(m, "id").trim()) : -1;
			if (0 <= id) sb.append(I3).append("public const int template_id = ").append(id).append("; // SBE message id\n");
			if (attr(m, "semanticType") != null) sb.append(I3).append("public const string SEMANTIC_TYPE = ").append(str(attr(m, "semanticType"))).append("; // FIX MsgType\n");
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(name);
			Set<String> nested = new HashSet<>();
			members(m, sb, I3, fieldNames, nested);
			sb.append(I2).append("}\n");
			messages.append(sb);
			dashboard.put(name, null);
			messagePacks.add(name);
		}

		/** Fields, groups and var-data of a message or group body. */
		void members(Element container, StringBuilder sb, String indent, Set<String> fieldNames, Set<String> nestedNames) {
			for (Element f : children(container)) {
				String fname = attr(f, "name");
				switch (local(f)) {
					case "field": {
						String fn = unique(fname, fieldNames);
						doc(sb, indent, attr(f, "description"));
						if ("constant".equals(attr(f, "presence"))) {
							sb.append(indent).append(constantField(f, fn)).append('\n');
							break;
						}
						String type = attr(f, "type");
						boolean optional = "optional".equals(attr(f, "presence"));
						Element target = registry.get(type);
						String decl, csType;
						if (target == null) {
							Prim p = Prim.of(type, 1);
							if (p == null) {
								System.err.println("WARNING " + file.getFileName() + ": field " + fname + " has unknown type `" + type + "`");
								decl = "byte " + fn + "; // unknown type " + type;
								csType = "";
							} else {
								decl = p.field(fn, optional);
								csType = p.len == 1 ? p.cs : "";
							}
						} else {
							decl = typedField(target, fn, optional);
							csType = scalarOf(target);
						}
						String hint = physicsHint(fname, attr(f, "description"), csType, floorOnly(target));
						if (hint != null) sb.append(indent).append("// ").append(hint).append('\n');
						sb.append(indent).append(attrsOf(f, decl)).append('\n');
						break;
					}
					case "group": {
						String cls = unique(capitalize(fname) + "Group", nestedNames);
						String fn = unique(fname, fieldNames);
						doc(sb, indent, attr(f, "description"));
						String dim = attr(f, "dimensionType", "groupSizeEncoding");
						long max = groupMax(dim);
						sb.append(indent).append(attrsOf(f, "[D(" + max + ")] " + cls + "[,,] " + fn + ";")).append('\n');
						sb.append(indent).append("public class ").append(cls).append(" {\n");
						Set<String> gf = new HashSet<>();
						gf.add(cls);
						members(f, sb, indent + I1, gf, new HashSet<>());
						sb.append(indent).append("}\n");
						break;
					}
					case "data": {
						String fn = unique(fname, fieldNames);
						doc(sb, indent, attr(f, "description"));
						sb.append(indent).append(attrsOf(f, varData(attr(f, "type"), fn))).append('\n');
						break;
					}
					default: break;
				}
			}
		}

		static String capitalize(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

		long groupMax(String dimensionType) {
			Element dim = registry.get(dimensionType);
			if (dim != null)
				for (Element m : children(dim, "type"))
					if ("numInGroup".equals(attr(m, "name"))) {
						Prim p = Prim.of(attr(m, "primitiveType"), 1);
						if (p != null) return Math.min(p.max, MAX_VAR_LENGTH);
					}
			return 255;
		}

		/** {@code <data>} field: resolves the var encoding composite (length + varData members). */
		String varData(String encoding, String fn) {
			Element enc = registry.get(encoding);
			long max = 255, declared = 255;
			String charset = null;
			boolean capped = false;
			if (enc != null)
				for (Element m : children(enc, "type")) {
					String n = attr(m, "name");
					if ("length".equals(n)) {
						Prim p = Prim.of(attr(m, "primitiveType"), 1);
						declared = attr(m, "maxValue") != null && isInteger(attr(m, "maxValue")) ? Long.parseLong(attr(m, "maxValue")) : p == null ? 255 : p.max;
						max = declared;
						if (MAX_VAR_LENGTH < max) {
							max = MAX_VAR_LENGTH;
							capped = true;
							cappedLength = true;
						}
					} else if ("varData".equals(n)) charset = attr(m, "characterEncoding");
				}
			List<String> a = new ArrayList<>();
			a.add(charset != null ? "D(+" + max + ")" : "D(" + max + ")");
			if (charset != null) a.add("CharacterEncoding(" + str(charset) + ")");
			if (capped) a.add("DeclaredMaxLength(" + declared + ")");
			return "[" + String.join(", ", a) + "] " + (charset != null ? "string " : "Binary[,,] ") + fn + ";";
		}

		/** The AdHoc scalar behind a named `<type>` declaration, or "" when the target is not a single scalar. */
		String scalarOf(Element target) {
			if (target == null || !local(target).equals("type") || "constant".equals(attr(target, "presence"))) return "";
			Prim p = prim(target);
			return p.len == 1 ? p.cs : "";
		}

		/** The declared minimum of a `<type>` that states a floor and no ceiling, otherwise null. */
		String floorOnly(Element target) {
			if (target == null || !local(target).equals("type")) return null;
			String min = attr(target, "minValue"), max = attr(target, "maxValue");
			return min != null && max == null && isInteger(min) && !"".equals(scalarOf(target)) ? min.trim() : null;
		}

		/** A field whose type is a named declaration (typedef / composite / enum / set / plain or constant type). */
		String typedField(Element target, String fn, boolean optional) {
			switch (local(target)) {
				case "type": {
					if ("constant".equals(attr(target, "presence"))) return constant(target, fn, text(target));
					String tn = declare(target, null);
					Prim p = prim(target);
					boolean opt = optional || p.optional;
					if (tn == null) return p.field(fn, opt);                           // plain alias: inline primitive
					boolean reference = p.isChar && p.len != 1 || p.len != 1;         // string / array typedef: never `?`
					return tn + (opt && !reference ? "?" : "") + " " + fn + ";";        // constrained: TYPEDEF
				}
				case "composite": {
					String tn = declare(target, null);
					return tn + " " + fn + ";";
				}
				case "enum":
				case "set": {
					String tn = declare(target, null);
					List<Element> vals = children(target, local(target).equals("enum") ? "validValue" : "choice");
					if (vals.size() < 2) { // constants container, keep the encoding primitive
						Prim enc = encoding(attr(target, "encodingType"));
						return enc.field(fn, optional || enc.optional) + " // values: " + tn;
					}
					return tn + (optional ? "?" : "") + " " + fn + ";";
				}
				default: return "byte " + fn + "; // unsupported " + local(target);
			}
		}

		/** A message field with {@code presence="constant"}: valueRef into an enum or a literal body. */
		String constantField(Element f, String fn) {
			String valueRef = attr(f, "valueRef");
			String type = attr(f, "type");
			if (valueRef != null) {
				int dot = valueRef.indexOf('.');
				Element en = dot < 0 ? null : registry.get(valueRef.substring(0, dot));
				if (en != null)
					for (Element v : children(en, "validValue"))
						if (attr(v, "name").equals(valueRef.substring(dot + 1))) {
							Prim enc = encoding(attr(en, "encodingType"));
							return "const " + enc.cs + " " + fn + " = " + enumLiteral(enc, text(v)) + "; // " + valueRef;
						}
				return "const string " + fn + " = " + str(valueRef) + "; // unresolved valueRef";
			}
			Element target = registry.get(type);
			if (target != null && local(target).equals("type")) return constant(target, fn, text(f).isEmpty() ? text(target) : text(f));
			Prim p = target == null ? Prim.of(type, 1) : null;
			return constant(p == null ? Prim.of("uint8", 1) : p, fn, text(f));
		}

		String constant(Element t, String fn, String value) { return constant(prim(t), fn, value); }

		static String constant(Prim p, String fn, String value) {
			if (p.isChar) {
				if (value.length() == 1) return "const char " + fn + " = " + charLiteral(value) + ";";
				return "const string " + fn + " = " + str(value) + ";";
			}
			if (p.cs.equals("float") || p.cs.equals("double")) return "const " + p.cs + " " + fn + " = " + AdHocWriter.num(value) + (p.cs.equals("float") ? "f" : "") + ";";
			return "const " + constType(p.cs) + " " + fn + " = " + (isInteger(value) ? value.trim() : "0 /* " + value + " */") + ";";
		}

		/**
		 * AdHocAgent throws InvalidCastException in ConstantImpl.init_exT on `const sbyte` / `const short`, so a
		 * constant of those encodings is widened to `int`. Nothing on the wire changes: a constant is never
		 * transmitted, and the SBE encoding stays recorded in the field's own attributes.
		 */
		static String constType(String cs) { return cs.equals("sbyte") || cs.equals("short") ? "int" : cs; }

		/** Metadata of an SBE element (message, field, group, data, composite member) as attribute expressions. */
		List<String> attrList(Element e) {
			List<String> a = new ArrayList<>();
			if (!local(e).equals("message") && isInteger(attr(e, "id", ""))) a.add("Tag(" + attr(e, "id") + ")");
			if (isInteger(attr(e, "sinceVersion", ""))) a.add("SinceVersion(" + attr(e, "sinceVersion") + ")");
			if (isInteger(attr(e, "deprecated", ""))) a.add("Deprecated(" + attr(e, "deprecated") + ")");
			if (attr(e, "semanticType") != null && !local(e).equals("message")) a.add("SemanticType(" + str(attr(e, "semanticType")) + ")");
			if (attr(e, "dimensionType") != null) a.add("DimensionType(" + str(attr(e, "dimensionType")) + ")");
			if (isInteger(attr(e, "blockLength", ""))) a.add("BlockLength(" + attr(e, "blockLength") + ")");
			return a;
		}

		/** Same as {@link #prepend(List, String)} for the element's own attribute list. */
		String attrsOf(Element e, String decl) { return prepend(attrList(e), decl); }

		/**
		 * Merges attributes into a declaration that may already start with its own {@code [...]} group, keeping one
		 * group and dropping an attribute the declaration already carries (a field and its type may both name a
		 * {@code semanticType}; C# forbids applying the same attribute twice).
		 */
		static String prepend(List<String> attrs, String decl) {
			List<String> keep = new ArrayList<>();
			for (String a : attrs) {
				String name = a.substring(0, a.indexOf('(') < 0 ? a.length() : a.indexOf('('));
				if (!decl.contains("[" + name + "(") && !decl.contains(", " + name + "(") && !decl.contains("[" + name + "]") && !decl.contains(", " + name + "]")) keep.add(a);
			}
			if (keep.isEmpty()) return decl;
			if (decl.startsWith("[")) return "[" + String.join(", ", keep) + ", " + decl.substring(1);
			return "[" + String.join(", ", keep) + "] " + decl;
		}

		Prim encoding(String encodingType) {
			if (encodingType == null) return Prim.of("uint8", 1);
			Prim p = Prim.of(encodingType, 1);
			if (p != null) return p;
			Element t = registry.get(encodingType);
			if (t != null && local(t).equals("type")) return prim(t);
			return Prim.of("uint8", 1);
		}

		Prim prim(Element t) {
			String pt = attr(t, "primitiveType", "uint8");
			int len = isInteger(attr(t, "length", "1")) ? Integer.parseInt(attr(t, "length", "1")) : 1;
			Prim p = Prim.of(pt, len);
			if (p == null) {
				System.err.println("WARNING " + file.getFileName() + ": unknown primitiveType `" + pt + "` in " + attr(t, "name"));
				p = Prim.of("uint8", len);
			}
			p.optional = "optional".equals(attr(t, "presence"));
			p.nullValue = attr(t, "nullValue");
			p.min = attr(t, "minValue");
			p.max_ = attr(t, "maxValue");
			p.charset = attr(t, "characterEncoding");
			p.semantic = attr(t, "semanticType");
			p.constrained = p.optional || p.nullValue != null || (p.min != null && p.max_ != null) || p.charset != null || 1 < len || len == 0;
			return p;
		}
	}

	/** An SBE primitive encoding with its constraints, rendered as an AdHoc field. */
	static final class Prim {
		final String sbe, cs;
		final int len;
		final long max;
		final boolean isChar;
		boolean optional, constrained;
		String nullValue, min, max_, charset, semantic;

		Prim(String sbe, String cs, int len, long max) {
			this.sbe = sbe;
			this.cs = cs;
			this.len = len;
			this.max = max;
			this.isChar = sbe.equals("char");
		}

		static Prim of(String primitiveType, int len) {
			switch (primitiveType) {
				case "char": return new Prim("char", "char", len, 255);
				case "int8": return new Prim("int8", "sbyte", len, 127);
				case "uint8": return new Prim("uint8", "byte", len, 255);
				case "int16": return new Prim("int16", "short", len, 32767);
				case "uint16": return new Prim("uint16", "ushort", len, 65535);
				case "int32": return new Prim("int32", "int", len, Integer.MAX_VALUE);
				case "uint32": return new Prim("uint32", "uint", len, 4294967295L);
				case "int64": return new Prim("int64", "long", len, Long.MAX_VALUE);
				case "uint64": return new Prim("uint64", "ulong", len, Long.MAX_VALUE);
				case "float": return new Prim("float", "float", len, 0);
				case "double": return new Prim("double", "double", len, 0);
				default: return null;
			}
		}

		String field(String name) { return field(name, optional); }

		String field(String name, boolean opt) {
			List<String> a = new ArrayList<>();
			String type;
			if (isChar && len != 1) {                       // char[N] / char[0] → string
				a.add("D(+" + (len == 0 ? 65535 : len) + ")");
				type = "string";
			} else if (len != 1) {                          // T[N] / T[0]
				a.add(len == 0 ? "D(65535)" : "D(" + len + ")");
				type = (sbe.equals("uint8") ? "Binary" : cs) + (len == 0 ? "[,,]" : "[]");
			} else type = cs + (opt ? "?" : "");
			if (min != null && max_ != null && isInteger(min) && isInteger(max_) && !isChar && !cs.equals("float") && !cs.equals("double"))
				a.add("MinMax(" + min.trim() + ", " + max_.trim() + ")");
			if (nullValue != null) a.add("NullValue(" + str(nullValue) + ")");
			if (charset != null) a.add("CharacterEncoding(" + str(charset) + ")");
			if (semantic != null) a.add("SemanticType(" + str(semantic) + ")");
			return (a.isEmpty() ? "" : "[" + String.join(", ", a) + "] ") + type + " " + name + ";";
		}
	}

	// ═══════════════════════════════════════════ QuickFIX ═══════════════════════════════════════════

	/** QuickFIX data dictionary → one AdHoc project. */
	static final class QuickFix {
		final Path file;
		final Element root;
		final String project;
		final Map<String, Element> fieldDefs = new LinkedHashMap<>();       // by name
		final Map<String, Element> components = new LinkedHashMap<>();      // by name
		final Map<String, String> componentClass = new LinkedHashMap<>();   // name → emitted class
		final Map<String, String> enumOf = new LinkedHashMap<>();           // field name → enum type name (real enums only)
		final Map<String, String> valuesOf = new LinkedHashMap<>();         // field name → constants container name
		final Set<String> projectNames = new HashSet<>();
		final StringBuilder out = new StringBuilder(1 << 20);
		final Map<String, Integer> dashboard = new LinkedHashMap<>();
		final List<String> messagePacks = new ArrayList<>();

		QuickFix(Path file, Element root) {
			this.file = file;
			this.root = root;
			this.project = projectName(file);
			projectNames.addAll(Arrays.asList(project, "Initiator", "Acceptor", "Session", "Header", "Trailer"));
		}

		String convert() {
			for (Element fields : children(root, "fields"))
				for (Element f : children(fields, "field")) fieldDefs.put(attr(f, "name"), f);
			for (Element comps : children(root, "components"))
				for (Element c : children(comps, "component")) components.put(attr(c, "name"), c);
			List<Element> msgs = new ArrayList<>();
			for (Element ms : children(root, "messages")) msgs.addAll(children(ms, "message"));

			// Reserve names: messages, components, then enums / value containers.
			Map<Element, String> msgNames = new java.util.IdentityHashMap<>();
			for (Element m : msgs) msgNames.put(m, unique(attr(m, "name"), projectNames));
			for (Element c : components.values()) componentClass.put(attr(c, "name"), unique(attr(c, "name"), projectNames));

			StringBuilder enums = new StringBuilder();
			for (Element f : fieldDefs.values()) enumOrValues(f, enums);

			out.append(I2).append("// ═════════════════════════ standard header / trailer ═════════════════════════\n");
			out.append(I2).append("// Plain packs, not HeaderFor<>: FIX header fields are strings and optional, which AdHoc headers do not allow.\n");
			for (Element h : children(root, "header"))
				if (isEmpty(h)) out.append(I2).append("// <header> is empty in this dictionary (FIX 5 sessions use the FIXT transport dictionary).\n");
				else { container(h, "Header", null, null); hasHeader = true; }
			for (Element t : children(root, "trailer"))
				if (isEmpty(t)) out.append(I2).append("// <trailer> is empty in this dictionary (FIX 5 sessions use the FIXT transport dictionary).\n");
				else { container(t, "Trailer", null, null); hasTrailer = true; }

			out.append('\n').append(I2).append("// ═════════════════════════ messages ═════════════════════════\n");
			for (Element m : msgs) {
				String name = msgNames.get(m);
				container(m, name, attr(m, "msgtype"), attr(m, "msgcat"));
				dashboard.put(name, null);
				messagePacks.add(name);
			}
			out.append('\n').append(I2).append("// ═════════════════════════ components ═════════════════════════\n");
			for (Element c : components.values())
				if (isEmpty(c)) out.append(I2).append("// component ").append(attr(c, "name")).append(" is declared without members in this dictionary and is not emitted.\n");
				else container(c, componentClass.get(attr(c, "name")), null, null);
			out.append('\n').append(I2).append("// ═════════════════════════ field value sets ═════════════════════════\n");
			out.append(enums);

			StringBuilder sb = new StringBuilder(out.length() + 8192);
			AdHocWriter.fileHeader(sb, "FIX2AdHoc (QuickFIX data dictionary)", file.getFileName().toString(),
					"FIX " + attr(root, "type", "") + " " + attr(root, "major", "") + "." + attr(root, "minor", "") + " SP" + attr(root, "servicepack", "0")
					+ ": " + msgs.size() + " messages, " + components.size() + " components, " + fieldDefs.size() + " fields");
			sb.append("namespace org.fix {\n");
			AdHocWriter.dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(project).append(" {\n\n");
			defaultMaxLength(sb);
			sb.append(out);
			sb.append('\n').append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			AdHocWriter.host(sb, I2, "Initiator", "The side that sends Logon.");
			AdHocWriter.host(sb, I2, "Acceptor", "The side that accepts the session.");
			AdHocWriter.connectionOpen(sb, I2, "Session", "Initiator", "Acceptor");
			sb.append(I3).append("// Every FIX message (admin and application), either direction; components and groups stay sub-packs.\n");
			AdHocWriter.statePacks(sb, I3, "_____lr_____", "Established", messagePacks);
			sb.append(I2).append("}\n\n");
			sb.append(I2).append("// ═════════════════════════ FIX metadata attributes ═════════════════════════\n\n");
			AdHocWriter.attribute(sb, I2, "Tag", "FIX tag number of the field.", "int tag");
			AdHocWriter.attribute(sb, I2, "FixType", "FIX data type of the field where the C# type is an approximation.", "string type");
			AdHocWriter.attribute(sb, I2, "Values", "Name of the constants container holding the field's enumerated values.", "string container");
			sb.append(I1).append("}\n}\n");
			return sb.toString();
		}

		boolean hasHeader, hasTrailer;

		/** A container (header, trailer, component, group) that would produce a pack without fields; an empty pack as a field type is only a bool. */
		boolean isEmpty(Element c) {
			for (Element m : children(c))
				switch (local(m)) {
					case "field":
					case "group": return false;
					case "component": {
						Element def = components.get(attr(m, "name"));
						if (def != null && !isEmpty(def)) return false;
						break;
					}
					default: break;
				}
			return true;
		}

		/** Header, trailer, message or component: a class with its fields, components and nested groups. */
		void container(Element c, String name, String msgtype, String msgcat) {
			out.append('\n');
			out.append(I2).append("class ").append(name).append(" {\n");
			if (msgtype != null) {
				out.append(I3).append("public const string MSG_TYPE = ").append(str(msgtype)).append(";\n");
				if (msgcat != null) out.append(I3).append("public const string MSG_CAT = ").append(str(msgcat)).append(";\n");
				if (hasHeader) out.append(I3).append("Header header;\n");
			}
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(name);
			if (msgtype != null) fieldNames.add("header");
			if (msgtype != null) fieldNames.add("trailer");
			members(c, out, I3, fieldNames, new HashSet<>());
			if (msgtype != null && hasTrailer) out.append(I3).append("Trailer trailer;\n");
			out.append(I2).append("}\n");
		}

		void members(Element container, StringBuilder sb, String indent, Set<String> fieldNames, Set<String> nestedNames) {
			for (Element m : children(container)) {
				String mname = attr(m, "name");
				boolean required = "Y".equals(attr(m, "required"));
				switch (local(m)) {
					case "field": {
						String fn = unique(mname, fieldNames);
						String decl = field(mname, fn, required);
						String hint = lastFieldHint;
						if (hint != null) sb.append(indent).append("// ").append(hint).append('\n');
						sb.append(indent).append(decl).append('\n');
						break;
					}
					case "component": {
						String cls = componentClass.get(mname);
						Element def = components.get(mname);
						if (cls == null || def == null) {
							System.err.println("WARNING " + file.getFileName() + ": unknown component `" + mname + "`");
							break;
						}
						if (isEmpty(def)) {
							sb.append(indent).append("// component ").append(mname).append(" has no members in this dictionary\n");
							break;
						}
						String fn = unique(lowerFirst(mname), fieldNames);
						sb.append(indent).append(cls).append(' ').append(fn).append(";\n");
						break;
					}
					case "group": {
						String cls = unique(mname + "Group", nestedNames);
						String fn = unique(mname, fieldNames);
						Element count = fieldDefs.get(mname);
						String tag = count == null ? "" : "[Tag(" + attr(count, "number") + ")] ";
						sb.append(indent).append(tag).append(cls).append("[,,] ").append(fn).append(";\n");
						sb.append(indent).append("public class ").append(cls).append(" {\n");
						Set<String> gf = new HashSet<>();
						gf.add(cls);
						members(m, sb, indent + I1, gf, new HashSet<>());
						sb.append(indent).append("}\n");
						break;
					}
					default: break;
				}
			}
		}

		static String lowerFirst(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

		/** Set by {@link #field} to the varint candidate of the field it just rendered, or null. */
		String lastFieldHint;

		/** One field reference: C# type from the FIX type, enum when the field has an enumerated value set. */
		String field(String fixName, String fn, boolean required) {
			lastFieldHint = null;
			Element def = fieldDefs.get(fixName);
			if (def == null) {
				System.err.println("WARNING " + file.getFileName() + ": field `" + fixName + "` is not declared in <fields>");
				return "string " + fn + "; // undeclared field";
			}
			String type = attr(def, "type", "STRING");
			List<String> a = new ArrayList<>();
			a.add("Tag(" + attr(def, "number") + ")");
			String cs;
			boolean value = true;                 // value type → `?` when optional
			boolean nativeConcept = false;        // AdHoc models the concept itself: no [FixType] needed
			switch (type) {
				// Counters and lengths: FIX states they start at a floor and are unbounded above, which is exactly
				// what [A] says - the wire carries `value - min`, so the common small values cost one byte.
				case "LENGTH": case "NUMINGROUP": a.add("A"); cs = "int"; break;
				case "SEQNUM": a.add("A"); cs = "long"; break;
				case "INT": case "DAYOFMONTH": case "TAGNUM": cs = "int"; break;
				case "FLOAT": case "PRICE": case "QTY": case "AMT": case "PERCENTAGE": case "PRICEOFFSET": cs = "double"; break;
				case "CHAR": cs = "char"; break;
				case "BOOLEAN": cs = "bool"; break;
				// Absolute instants: AdHoc has DateTime, so the concept is mapped, not described by an attribute.
				// LOCALMKTDATE / MONTHYEAR / LOCALMKTTIME / TZ* are not absolute instants and stay strings.
				case "UTCTIMESTAMP": case "UTCDATEONLY": case "UTCDATE": case "UTCTIMEONLY":
					cs = "DateTime";
					nativeConcept = true;
					break;
				case "DATA": case "XMLDATA": cs = "[D(65535)] Binary[,,]"; value = false; break;
				default: cs = "string"; value = false; break;
			}
			if (!nativeConcept && !type.equals("INT") && !type.equals("STRING") && !type.equals("CHAR") && !type.equals("BOOLEAN"))
				a.add("FixType(" + str(type) + ")");
			String en = enumOf.get(fixName);
			if (en != null) { cs = en; value = true; }
			String vals = valuesOf.get(fixName);
			if (vals != null) a.add("Values(" + str(vals) + ")");
			// An enumerated field is a code, not a magnitude, and a field that already carries [A] needs no hint.
			if (en == null && !a.contains("A")) lastFieldHint = physicsHint(fixName, null, cs, null);
			String attrs = "[" + String.join(", ", a) + "] ";
			if (cs.startsWith("[D(")) return "[" + String.join(", ", a) + ", " + cs.substring(1) + " " + fn + ";";
			return attrs + cs + (value && !required ? "?" : "") + " " + fn + ";";
		}

		/** Emits an enum (numeric or char codes) or a constants container (string codes) for a field with values. */
		void enumOrValues(Element def, StringBuilder sb) {
			List<Element> values = children(def, "value");
			if (values.isEmpty()) return;
			String type = attr(def, "type", "STRING");
			if (type.equals("BOOLEAN")) return;                      // Y/N → bool
			String fixName = attr(def, "name");
			boolean allInt = true, allChar = true;
			Map<String, String> codes = new LinkedHashMap<>();       // code → member name (first description wins)
			Set<String> memberNames = new HashSet<>();
			for (Element v : values) {
				String code = attr(v, "enum", "");
				if (codes.containsKey(code)) continue;
				codes.put(code, unique(attr(v, "description", "VALUE"), memberNames));
				if (!isInteger(code)) allInt = false;
				if (code.length() != 1) allChar = false;
			}
			boolean numeric = allInt && (type.equals("INT") || type.equals("NUMINGROUP") || type.equals("LENGTH") || type.equals("SEQNUM") || type.equals("DAYOFMONTH") || type.equals("TAGNUM"));
			boolean chars = !numeric && allChar && (type.equals("CHAR") || type.equals("STRING") || type.equals("MULTIPLECHARVALUE"));
			if ((numeric || chars) && 2 <= codes.size()) {
				String en = unique(fixName, projectNames);
				enumOf.put(fixName, en);
				sb.append('\n').append(I2).append("/** Tag ").append(attr(def, "number")).append(", FIX type ").append(type).append(". */\n");
				sb.append(I2).append("enum ").append(en).append(" {\n");
				for (Map.Entry<String, String> e : codes.entrySet())
					sb.append(I3).append(e.getValue()).append(" = ").append(numeric ? e.getKey().trim() : charLiteral(e.getKey())).append(",\n");
				sb.append(I2).append("}\n");
			} else {
				String vn = unique(fixName + "Values", projectNames);
				valuesOf.put(fixName, vn);
				sb.append('\n').append(I2).append("/** Enumerated values of tag ").append(attr(def, "number")).append(" (").append(fixName).append(", FIX type ").append(type).append("). */\n");
				sb.append(I2).append("public struct ").append(vn).append(" {\n");
				for (Map.Entry<String, String> e : codes.entrySet())
					sb.append(I3).append("public const string ").append(e.getValue()).append(" = ").append(str(e.getKey())).append(";\n");
				sb.append(I2).append("}\n");
			}
		}
	}
}
