package link.thesymbolic.jetcommand

import android.app.Application
import link.thesymbolic.jetcommand.night.NightLogDatabase
import link.thesymbolic.jetcommand.night.RoomNightLogStore

class JetCommandApp : Application() {
    lateinit var controller: BedJetController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = BedJetController(this, RoomNightLogStore(NightLogDatabase.open(this).events()))
    }
}

val android.content.Context.controller get() = (applicationContext as JetCommandApp).controller
