package link.thesymbolic.jetcommand

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import link.thesymbolic.jetcommand.protocol.CommandOrigin
import link.thesymbolic.jetcommand.protocol.LogLevel

/**
 * The target of shortcuts.xml, and through it of Samsung Routines' app actions. Theme NoDisplay:
 * finish() must run before onResume or the system throws.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.dataString
        val command = CommandUri.parse(data)
        if (command != null) {
            controller.execute(command, CommandOrigin.SHORTCUT)
        } else {
            controller.log(LogLevel.ERROR, "shortcut: unknown command $data")
            Toast.makeText(this, getString(R.string.command_unknown), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
