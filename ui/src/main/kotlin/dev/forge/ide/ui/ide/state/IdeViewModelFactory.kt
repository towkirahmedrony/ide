package dev.forge.ide.ui.ide.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Minimal factory so ViewModels can be built with the contracts supplied by
 * [dev.forge.ide.ui.ide.IdeDependencies] without introducing a DI framework.
 */
class IdeViewModelFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create() as T
}
