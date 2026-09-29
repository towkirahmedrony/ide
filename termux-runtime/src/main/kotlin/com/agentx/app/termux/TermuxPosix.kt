package com.agentx.app.termux

import android.system.Os
import java.io.File

interface TermuxPosix {
    fun chmodOwnerExecute(path: String)
    fun symlink(target: String, linkPath: String)
    fun canExecute(file: File): Boolean
}

object AndroidTermuxPosix : TermuxPosix {
    override fun chmodOwnerExecute(path: String) {
        Os.chmod(path, OWNER_EXECUTE)
    }

    override fun symlink(target: String, linkPath: String) {
        Os.symlink(target, linkPath)
    }

    override fun canExecute(file: File): Boolean = file.isFile && file.canExecute()

    const val OWNER_EXECUTE: Int = 0b111_000_000
}
