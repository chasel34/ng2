package com.chasel.ng2n.core.net

data class Credential(
  val uid: String,
  val token: String,
)

interface CredentialSource {

  suspend fun current(): Credential?

  suspend fun all(): List<Credential>
}
