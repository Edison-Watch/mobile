package ai.sealgate.stdiod.tunnel

import ai.sealgate.stdiod.mcp.LocalMcpModule

/**
 * The dashboard "command" that marks a local server as one of this app's
 * built-in modules. Anything else would need a subprocess, which a phone
 * cannot spawn.
 */
const val BUILTIN_COMMAND = "mobile-builtin"

/**
 * Pick the built-in module a desired server should be served by.
 *
 * The SealGate prefix (`server.name`) is unique per organisation, so two
 * people in the same org cannot both call their phone `mobilebash`. The
 * prefix therefore cannot be the only way to find a module. Resolution order:
 *
 * 1. A module whose name equals the prefix (the original convention).
 * 2. Otherwise, when the command is [BUILTIN_COMMAND]: the module named by
 *    the first argument, or - with no arguments - the only exposed module.
 *
 * Returns null when nothing matches; the caller reports a spawn error.
 */
fun resolveBuiltinModule(
    server: DesiredServer,
    modulesByName: Map<String, LocalMcpModule>,
): LocalMcpModule? {
    modulesByName[server.name]?.let { return it }
    if (server.command != BUILTIN_COMMAND) return null
    val requested = server.args.firstOrNull()
    if (requested != null) return modulesByName[requested]
    return modulesByName.values.singleOrNull()
}

/** Human-readable reason for refusing to bind [server]. */
fun describeUnboundServer(server: DesiredServer, moduleNames: Collection<String>): String {
    val available = moduleNames.sorted()
    return if (server.command == BUILTIN_COMMAND) {
        "no built-in module matches server `${server.name}` on this Android device; " +
            "pass one of $available as the first argument, or use it as the prefix"
    } else {
        "command `${server.command}` is not `$BUILTIN_COMMAND`; this Android device " +
            "cannot spawn subprocesses and only serves built-in modules $available"
    }
}
