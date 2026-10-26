package com.bori.test.t3

object Native {
    init { System.loadLibrary("t3") }
    @JvmStatic external fun answer(): Int
}
