(ns graphden.util.hex
  "Lowercase hexadecimal bytes on the JVM and in Babashka tooling."
  #?@(:bb
      []
      :clj
      [(:import
         (java.util
           HexFormat))]))


#?(:bb
   (defn encode
     "Encode every byte, including leading zeroes and signed byte values."
     [byte-values]
     (apply str (map #(format "%02x" (bit-and % 0xff)) byte-values)))
   :clj
   (defn encode
     "Encode every byte, including leading zeroes and signed byte values."
     [byte-values]
     (HexFormat/.formatHex (HexFormat/of) ^bytes byte-values)))
