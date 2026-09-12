package com.chasel.ng2n.ui.common

sealed interface SignedInGate {
  data class Proceed(val uid: String) : SignedInGate

  data class NeedLogin(val message: String) : SignedInGate
}

fun signedInGate(uid: String?, message: String): SignedInGate =
  if (uid == null) SignedInGate.NeedLogin(message) else SignedInGate.Proceed(uid)
