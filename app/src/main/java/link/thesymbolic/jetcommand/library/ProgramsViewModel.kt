package link.thesymbolic.jetcommand.library

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import link.thesymbolic.jetcommand.FakePrograms
import link.thesymbolic.jetcommand.ProgramsSource
import link.thesymbolic.jetcommand.controller
import link.thesymbolic.jetcommand.library

/**
 * Holds the program screens' [ProgramsSource] across rotations. [handle] carries the launch
 * intent's extras: `--ez fake_library true` swaps in [FakePrograms] in a debuggable build.
 */
class ProgramsViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    val source: ProgramsSource =
        if (
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                handle.get<Boolean>(FAKE_LIBRARY) == true
        )
            FakePrograms()
        else LibraryPrograms(app.library, app.controller, viewModelScope)

    private companion object {
        const val FAKE_LIBRARY = "fake_library"
    }
}
