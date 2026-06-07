function fallbackUuid() {
  const timestamp = Date.now().toString(16)
  const random = Array.from({ length: 24 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
  return `${timestamp.slice(0, 8)}-${random.slice(0, 4)}-4${random.slice(4, 7)}-a${random.slice(7, 10)}-${random.slice(10, 22)}`
}

export function createUuid() {
  const maybeCrypto = globalThis.crypto
  if (maybeCrypto?.randomUUID) {
    return maybeCrypto.randomUUID()
  }
  return fallbackUuid()
}
