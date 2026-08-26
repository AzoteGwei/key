/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.PathValidator;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.proof.io.ProblemLoaderException;

/**
 * Tools for loading projects and listing contracts.
 */
public class ProjectToolHandler extends ToolHandler {

    public ProjectToolHandler(ToolContext ctx) {
        super(ctx);
    }

    @Override
    public void registerTools(Map<String, ToolDefinition> tools) {
        register(tools, "key_project_load", "Load a KeY project from a directory or .key file.",
            props(
                "location", Map.of("type", "string", "description",
                    "Path to the project directory or .key file"),
                "classPaths", Map.of("type", "array", "items", Map.of("type", "string")),
                "bootClassPath", Map.of("type", "string"),
                "includes", Map.of("type", "array", "items", Map.of("type", "string"))),
            List.of("location"), this::handleProjectLoad);
        register(tools, "key_contracts_list",
            "List all verification contracts in the loaded project.", Map.of(), List.of(),
            this::handleContractsList);
    }

    private Map<String, Object> handleProjectLoad(Map<String, Object> params) {
        String location = ToolContext.requireString(params, "location");
        Path projectPath = PathValidator.resolveAndValidate(location, ctx.config().workspace(),
            ctx.config().allowedPaths());

        // All file parameters follow the same rules as 'location': relative paths are
        // resolved against the workspace and every path must pass the whitelist.
        List<Path> classPaths = resolvePathList(params.get("classPaths"));
        Path bootClassPath = params.get("bootClassPath") == null ? null
                : PathValidator.resolveAndValidate(params.get("bootClassPath").toString(),
                    ctx.config().workspace(), ctx.config().allowedPaths());
        List<Path> includes = resolvePathList(params.get("includes"));

        try {
            KeYEnvironment<?> env =
                KeYEnvironment.load(projectPath, classPaths, bootClassPath, includes);
            ctx.session().dispose();
            ctx.session().setEnvironment(env);
            ctx.session().loadContracts();

            Map<String, Object> result = Json.object();
            result.put("success", true);
            result.put("loadedTypes", env.getJavaInfo().getAllKeYJavaTypes().size());
            result.put("contractCount", ctx.session().getContracts().size());
            return result;
        } catch (ProblemLoaderException e) {
            throw new McpToolException(-32603, "Failed to load project: " + e.getMessage(),
                causeChain(e));
        }
    }

    private static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    private List<Path> resolvePathList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Path> paths = new ArrayList<>();
        for (Object o : list) {
            paths.add(PathValidator.resolveAndValidate(o.toString(), ctx.config().workspace(),
                ctx.config().allowedPaths()));
        }
        return paths;
    }

    private Map<String, Object> handleContractsList(Map<String, Object> params) {
        return Map.of("contracts", ctx.contractsListJson());
    }
}
