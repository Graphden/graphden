(ns graphden.accounts.session-read
  "Optional paired read over the accounts storage itself. Decorators that do
   not implement it keep their normal entity reads and permission checks.")


(defprotocol SessionAccountRead

  (session-and-account
    [storage token-hash]
    "Return the session and its account from one statement, or nil for a
     missing session. Omit :account to request ordinary account-id resolution.
     ::unsupported selects ordinary reads for an extended or older schema."))
