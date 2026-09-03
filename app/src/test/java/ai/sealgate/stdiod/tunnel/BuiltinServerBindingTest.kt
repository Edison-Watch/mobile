package ai.sealgate.stdiod.tunnel

import ai.sealgate.stdiod.mcp.LocalMcpModule
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinServerBindingTest {
    private class FakeModule(override val name: String) : LocalMcpModule {
        override fun handle(message: JsonObject): JsonObject? = null
    }

    private val bash = FakeModule("mobilebash")
    private val computer = FakeModule("computer")

    private fun server(
        name: String,
        command: String = BUILTIN_COMMAND,
        args: List<String> = emptyList(),
    ) = DesiredServer(serverId = name, name = name, command = command, args = args, enabled = true)

    @Test
    fun prefixEqualToModuleNameBinds() {
        val modules = mapOf(bash.name to bash, computer.name to computer)
        assertSame(bash, resolveBuiltinModule(server("mobilebash", command = "anything"), modules))
    }

    @Test
    fun customPrefixWithBuiltinCommandBindsTheOnlyModule() {
        val modules = mapOf(bash.name to bash)
        assertSame(bash, resolveBuiltinModule(server("mobilebash3"), modules))
    }

    @Test
    fun firstArgumentSelectsModuleWhenSeveralAreExposed() {
        val modules = mapOf(bash.name to bash, computer.name to computer)
        assertSame(computer, resolveBuiltinModule(server("phone", args = listOf("computer")), modules))
        assertNull(resolveBuiltinModule(server("phone", args = listOf("nope")), modules))
    }

    @Test
    fun ambiguousBuiltinWithoutArgumentIsRefused() {
        val modules = mapOf(bash.name to bash, computer.name to computer)
        assertNull(resolveBuiltinModule(server("phone"), modules))
    }

    @Test
    fun nonBuiltinCommandIsRefused() {
        val modules = mapOf(bash.name to bash)
        assertNull(resolveBuiltinModule(server("mobilebash3", command = "npx"), modules))
    }

    @Test
    fun refusalMessagesNameTheAvailableModules() {
        val names = listOf("mobilebash")
        val builtin = describeUnboundServer(server("phone"), names)
        assertTrue(builtin, builtin.contains("[mobilebash]") && builtin.contains("`phone`"))
        val npx = describeUnboundServer(server("phone", command = "npx"), names)
        assertTrue(npx, npx.contains("`npx`") && npx.contains(BUILTIN_COMMAND))
        assertEquals("mobile-builtin", BUILTIN_COMMAND)
    }
}
