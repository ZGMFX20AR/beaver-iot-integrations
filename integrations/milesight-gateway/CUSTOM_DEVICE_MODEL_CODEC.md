# Writing a codec for a custom (non-blueprint) device model

This is for onboarding a LoRaWAN device that isn't in Milesight's blueprint library -
any vendor, not just Milesight. The custom device model feature has nothing
Milesight-specific about it; the only real friction point is that different vendors
publish their payload decoders in different "standard" shapes, and Beaver IoT has its
own fixed contract that doesn't match any of them exactly. This document is that
contract, plus how to bridge the shapes you'll actually run into.

**Before reading further**: the custom device model editor has a **Test** button next
to the Decoder section. Paste a sample hex payload and fPort, and it runs your actual
code and shows the real decoded JSON or a real error message - instantly, without
saving the model or touching a device. Use it while writing or adapting a codec
instead of guessing; every example below is something you can verify this way as you
go.

## The contract, exactly

Confirmed directly from the executor (`CodecExecutor.java`,
`CustomDeviceModelService.java`), not assumed:

- Your entry function is called as `yourFunctionName(fPort, bytes)` - two plain
  positional arguments. No `variables` third argument is ever passed by this
  integration's custom device models.
- `fPort` arrives as a plain number.
- `bytes` arrives as a plain array of unsigned numbers, 0-255 each. Index access
  (`bytes[i]`) and `.length` work exactly like a normal JS array.
- Your function **must return a flat plain object** - no envelope. Whatever keys you
  return are matched *directly* against your entities' identifiers: case-sensitive,
  exact string match, no unwrapping of any `{data: {...}}`-style wrapper. A key that
  doesn't match any entity identifier is silently dropped - no error, nothing in the
  logs.
- The function name you enter in "Decoder Function Name" must exist as a real,
  callable function somewhere in your pasted code, or decoding fails immediately for
  every uplink (as of this integration's latest version, with a specific "Entry
  function 'X' not found in the code" message - older versions reported this as a
  bare, unhelpful exception).
- Your function must actually return an object. Returning a number, string, or
  nothing produces a clear "Decoder must return an object, got: &lt;type&gt;" error.

## The shapes you'll actually find in the wild

Pull a vendor's official decoder (from their product manual, their GitHub, or a
network server's device repository) and it'll be in one of these:

### 1. LoRa Alliance / TTN v3 / ChirpStack v4 "Payload Codec API"

The modern interoperability standard - most vendors publish against this today:

```js
function decodeUplink(input) {
    // input = { bytes, fPort, recvTime, variables }
    return { data: { temperature: 22.5 }, warnings: [], errors: [] };
}
```

Object-input, wrapped-output. Matches neither end of Beaver IoT's contract.

### 2. Legacy TTN v2 / ChirpStack v3 style

```js
function Decode(fPort, bytes, variables) { ... }
```

Positional args, same as Beaver IoT - but the return shape varies by vendor. Some
return a flat object directly (drop-in compatible, paste as-is). Others wrap it in
their own custom structure (e.g. a tracker decoder with a `{ data: { messages: [...] }
}` envelope, needing a real adapter unwrapping that specific shape).

## Adapters for the two shapes above

Keep the vendor's real decode logic completely untouched inside a renamed function;
only the wrapper changes.

**Shape 1 (modern standard):**

```js
// Beaver IoT calls (fPort, bytes) and expects a flat object - the vendor's function
// takes one input object and returns a { data } wrapper. Bridge the two.
// Set "Decoder Function Name" to: decodeUplink (this is already the field's own
// default, so leaving it blank picks this up automatically).
function decodeUplink(fPort, bytes) {
    var result = vendorDecodeUplink({ bytes: bytes, fPort: fPort });
    return (result && result.data) ? result.data : {};
}

// Paste the vendor's original function below, renamed from decodeUplink to
// vendorDecodeUplink, body otherwise unchanged.
function vendorDecodeUplink(input) {
    // ... vendor's real decode logic, using input.bytes / input.fPort ...
    return { data: {} };
}
```

**Shape 2 (legacy positional-args style):**

If the vendor's `Decode(fPort, bytes, variables)` already returns a flat object
directly (e.g. `return { temperature: 22.5 };`) - no adapter needed at all. Paste it
as-is, set "Decoder Function Name" to `Decode`. The unused third `variables` argument
is harmless; it's simply `undefined` since Beaver IoT never passes it.

If it wraps the result in the vendor's own custom structure instead, you need a
wrapper unwrapping that specific shape - there's no universal template for this part
since every vendor's internal structure differs:

```js
function Decode(fPort, bytes, variables) {
    var raw = vendorDecode(fPort, bytes, variables);
    // ... flatten raw's vendor-specific structure into a flat object here ...
    return {};
}

function vendorDecode(fPort, bytes, variables) {
    // ... vendor's real decode logic, renamed from Decode to vendorDecode ...
    return {};
}
```

## Checklist, same for every vendor

1. **Identify the shape** the vendor's decoder is written in (object input vs.
   positional args; wrapped vs. flat return).
2. **Adapt if needed**, using the templates above.
3. **Test it** with the editor's Test button before saving - paste a real sample
   payload from your device and confirm the decoded fields look right.
4. **Set "Decoder Function Name"** to match whatever your top-level entry function is
   actually called.
5. **Entity identifiers must exactly match** the flat keys your decoder returns -
   case-sensitive, no fuzzy matching.
6. **Device EUI must exactly match** what the gateway publishes in the uplink -
   mismatches fail before decode even runs.
7. **If you add entities to the model after a device already exists**, use the
   device's Resync Entities action (the sync icon next to it in the Synced Devices
   list) - editing a model doesn't retroactively add fields to already-created
   devices.
