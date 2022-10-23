package com.yandex.daggerlite.core.graph

import com.yandex.daggerlite.core.model.NodeDependency
import com.yandex.daggerlite.lang.Method
import com.yandex.daggerlite.validation.MayBeInvalid

/**
 * Graph-level abstraction over [com.yandex.daggerlite.core.ComponentModel.EntryPoint].
 */
interface GraphEntryPoint : MayBeInvalid {
    val getter: Method
    val dependency: NodeDependency
}