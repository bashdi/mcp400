package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.Capability;
import com.github.bashdi.mcp400.core.McpTools.Param;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;

import static com.github.bashdi.mcp400.core.McpTools.str;
import static com.github.bashdi.mcp400.core.McpTools.tool;

/** Programming capability: compile a local source on the IBM i (prepare_compile, compile_check). */
public final class PgmCapability implements Capability {

	private static final String INSTRUCTIONS = """
			Compile on the IBM i: prepare_compile copies a local source into QTEMP, compile_check compiles it and returns all diagnostics in one answer.
			Nothing is run. Temporary objects are removed by compile_check. Results are plain text lines.
			""";

	private final CompileService compiles = new CompileService();

	@Override
	public String instructions() {
		return INSTRUCTIONS;
	}

	@Override
	public List<SyncToolSpecification> tools() {
		return List.of(
				tool("prepare_compile",
						"Copies a local source (.rpgle, .sqlrpgle, .clle) into a temporary member in QTEMP on the IBM i. Returns compile_id.",
						List.of(new Param("source_path", "Path to the local source file", true),
								new Param("source_type", "Optional: RPGLE, SQLRPGLE or CLLE (otherwise from the file extension)", false),
								new Param("compiler_options", "Optional: *XREF, *NOXREF, *SECLVL, *NOSECLVL, separated by spaces", false),
								new Param("copy_members", "Optional: extra local copy member files, separated by ';'. Members missing here are searched in the folder of source_path", false),
								new Param("libraries", "Optional: IBM i libraries with the tables used by the source, separated by ','. Added to the library list", false)),
						a -> compiles.prepare(str(a, "source_path"), str(a, "source_type"), str(a, "compiler_options"), str(a, "copy_members"), str(a, "libraries"))),
				tool("compile_check",
						"Compiles the prepared source and returns diagnostics, joblog, listing and cleanup. Status: " + CompileService.STATUSES + ".",
						List.of(new Param("compile_id", "compile_id from prepare_compile", true)),
						a -> compiles.check(str(a, "compile_id"))),
				tool("search_objects",
						"Searches the objects of an IBM i library by name pattern (* wildcard) and object type. One line per object.",
						List.of(new Param("library", "IBM i library to search", true),
								new Param("name", "Optional: object name, * wildcard. Default *", false),
								new Param("object_type", "Optional: *PGM, *SRVPGM, *FILE, *MODULE, *DTAARA, *DTAQ, *MSGF, *OUTQ, *BNDDIR. Default *ALL", false)),
						a -> ObjectSearch.search(str(a, "library"), str(a, "name"), str(a, "object_type"))),
				tool("get_binding_directory_entries",
						"Entries (service programs, modules) of a binding directory (*BNDDIR).",
						List.of(new Param("library", "Library of the binding directory", true),
								new Param("directory", "Binding directory name", true)),
						a -> BindingSearch.entries(str(a, "library"), str(a, "directory"))),
				tool("search_procedures",
						"Searches exported procedures of service programs by name (* wildcard). Optionally only those bound by a binding directory.",
						List.of(new Param("pattern", "Procedure name, * wildcard", true),
								new Param("library", "Optional: library of the binding directory (needed with binding_directory)", false),
								new Param("binding_directory", "Optional: binding directory name", false)),
						a -> BindingSearch.procedures(str(a, "pattern"), str(a, "library"), str(a, "binding_directory"))));
	}
}
