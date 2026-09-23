# FIX-to-AdHoc — FIX SBE and QuickFIX dictionaries → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts the two machine-readable descriptions of the FIX trading protocol into
[AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` files, one per input:

| Input                                  | Root element              | What it describes                                                |
|:---------------------------------------|:--------------------------|:-----------------------------------------------------------------|
| **SBE** (Simple Binary Encoding) schema | `<sbe:messageSchema>`     | Fixed-layout binary messages: types, composites, enums, sets, messages with groups and var-data |
| **QuickFIX** data dictionary            | `<fix>`                   | Tag=value FIX: fields with enumerated values, components, repeating groups, messages, header, trailer |

The project is self-contained: Java 17, no dependencies beyond the JDK; `src/org/unirail/adhoc/` holds its own
copy of the AdHoc emitter helpers.

## Links

- SBE specification (FIX Trading Community): https://github.com/FIXTradingCommunity/fix-simple-binary-encoding
- SBE reference implementation and the sample schemas used here: https://github.com/aeron-io/simple-binary-encoding
- QuickFIX data dictionaries (FIX 4.0 … 5.0 SP2, FIXT 1.1): https://github.com/quickfix/quickfix/tree/master/spec
- FIX Orchestra, the machine-readable successor of the dictionaries (not converted here): https://github.com/FIXTradingCommunity/fix-orchestra
- AdHoc protocol description format: https://github.com/AdHoc-Protocol

## Samples (`fetch-samples.sh`)

SBE, from `aeron-io/simple-binary-encoding`: `example-schema.xml` + `common-types.xml` (XInclude), `example-extension-schema.xml`,
`car.xml`, `fix-message-samples.xml`, `basic-types-schema.xml`, `composite-elements-schema.xml`, `group-with-data-schema.xml`,
`FixBinary.xml` (a CME market-data schema, 29 messages).
QuickFIX, from `quickfix/quickfix/spec`: `FIX42.xml`, `FIX44.xml`, `FIX50SP2.xml`, `FIXT11.xml`.

## Commands

```bash
./fetch-samples.sh           # downloads the schemas above into samples/
./build.sh                   # javac → out/, then converts samples/ → AdHoc/
./validate.sh AdHoc          # AdHocAgent parse-only validation of every generated file (nothing is uploaded)

# manual run: one .cs per input, input may be a file or a folder
java -cp out org.unirail.FIX2AdHoc samples/FIX44.xml AdHoc
```

## Before and after

### SBE — a message with a repeating group

`samples/FixBinary.xml`, 960 lines — [source](samples/FixBinary.xml) → [result](AdHoc/FixBinary.cs)

```xml
<ns2:message name="ChannelReset4" id="4" description="ChannelReset" blockLength="9" semanticType="X">
    <field name="TransactTime" id="60" type="uInt64" description="Start of event processing time in number of nanoseconds since Unix epoch" offset="0" semanticType="UTCTimestamp"/>
    <field name="MatchEventIndicator" id="5799" type="MatchEventIndicator" description="Bitmap field of eight Boolean type indicators reflecting the end of updates for a given Globex event" offset="8" semanticType="MultipleCharValue"/>
    <group name="NoMDEntries" id="268" description="Number of entries in Market Data message" blockLength="2" dimensionType="groupSize">
        <field name="MDUpdateAction" id="279" type="MDUpdateTypeNew" description="Market Data update action" sinceVersion="2" semanticType="int"/>
        <field name="MDEntryType" id="269" type="MDEntryTypeChannelReset" description="Market Data entry type  " semanticType="char"/>
        <field name="ApplID" id="1180" type="Int16" description="Indicates the channel ID as defined in the XML configuration file" offset="0" sinceVersion="3" semanticType="int"/>
    </group>
</ns2:message>
<!-- … the composite that frames every repeating group … -->
<composite name="groupSize" description="Repeating group dimensions" semanticType="NumInGroup">
    <type name="blockLength" primitiveType="uint16"/>
    <type name="numInGroup" primitiveType="uint8"/>
</composite>
```

```csharp
[BlockLength(9)] class ChannelReset4 {
    public const int template_id = 4; // SBE message id
    public const string SEMANTIC_TYPE = "X"; // FIX MsgType
    /**
    Start of event processing time in number of nanoseconds since Unix epoch
    */
    [Tag(60), SemanticType("UTCTimestamp")] ulong TransactTime;
    /**
    Bitmap field of eight Boolean type indicators reflecting the end of updates for a given Globex event
    */
    [Tag(5799), SemanticType("MultipleCharValue")] MatchEventIndicator MatchEventIndicator;
    /**
    Number of entries in Market Data message
    */
    [Tag(268), DimensionType("groupSize"), BlockLength(2), D(255)] NoMDEntriesGroup[,,] NoMDEntries;
    public class NoMDEntriesGroup {
        // … the two constant-presence fields become `const`, so nothing of them reaches the wire …
        [Tag(279), SinceVersion(2), SemanticType("int")] const int MDUpdateAction = 0;
        [Tag(269), SemanticType("char")] const char MDEntryType = 'J';
        [Tag(1180), SinceVersion(3), SemanticType("int")] short ApplID;
    }
}

[SemanticType("NumInGroup")] class groupSize {    // the dimension composite stays a sub-pack
    ushort blockLength;
    byte numInGroup;
}
```

### QuickFIX — timestamps and sequence numbers in the standard header

`samples/FIX44.xml`, 6 599 lines — [source](samples/FIX44.xml) → [result](AdHoc/FIX44.cs)

```xml
<header>
 <field name='BeginString' required='Y' />
 <field name='BodyLength' required='Y' />
 <field name='MsgType' required='Y' />
 <field name='SecureDataLen' required='N' />
 <field name='SecureData' required='N' />
 <field name='MsgSeqNum' required='Y' />
 <!-- … -->
 <field name='PossDupFlag' required='N' />
 <field name='SendingTime' required='Y' />
 <field name='OrigSendingTime' required='N' />
</header>
<!-- … the <fields> section is where the types live … -->
<field number='34' name='MsgSeqNum' type='SEQNUM' />
<field number='52' name='SendingTime' type='UTCTIMESTAMP' />
<field number='9' name='BodyLength' type='LENGTH' />
```

```csharp
class Header {
    [Tag(8)] string BeginString;
    [Tag(9), A, FixType("LENGTH")] int BodyLength;
    [Tag(35), Values("MsgTypeValues")] string MsgType;
    // …
    [Tag(90), A, FixType("LENGTH")] int? SecureDataLen;
    [Tag(91), FixType("DATA"), D(65535)] Binary[,,] SecureData;
    [Tag(34), A, FixType("SEQNUM")] long MsgSeqNum;
    // …
    [Tag(43)] bool? PossDupFlag;
    [Tag(52)] DateTime SendingTime;
    [Tag(122)] DateTime? OrigSendingTime;
}
```

`UTCTIMESTAMP` became `DateTime` because AdHoc models an absolute instant natively; `SEQNUM` and `LENGTH` carry
`[A]` because FIX defines them as counters with a floor and no ceiling. Read the `[A]` caveat in the QuickFIX
mapping below before shipping a high-volume session.

## Shape of a generated file

`namespace org.fix`, `public interface <FileName>`; Packs Inventory on top; messages first, then types /
components / value sets; two hosts `Initiator` and `Acceptor`; connection `Session` with one non-transitional
state `Established` listing every message explicitly (`[_____lr_____<(A, B, …)>]`) so composites, components,
groups and typedefs stay sub-packs; custom attribute declarations at the end.

## SBE mapping

| SBE                                                       | AdHoc                                                                                                     |
|:----------------------------------------------------------|:----------------------------------------------------------------------------------------------------------|
| `<sbe:message id name>`                                   | `class Name { public const int template_id = id; … }` — the SBE template id is metadata, **not** the AdHoc pack id: AdHoc lays out its own wire and the agent assigns pack ids, so nothing is pinned in the Dashboard |
| message `semanticType` (FIX MsgType)                      | `const string SEMANTIC_TYPE`                                                                              |
| `<field id type>`                                         | field with `[Tag(id)]`; `presence="optional"` → `T?`; `sinceVersion` / `deprecated` → attributes          |
| `<field presence="constant" valueRef="Enum.X">`           | `const <encoding> name = <literal>; // Enum.X`                                                            |
| `<group dimensionType>`                                   | nested `class NameGroup` + `[D(max)] NameGroup[,,] name;` with `max` from the dimension's `numInGroup` width |
| `<data type="varStringEncoding">`                         | `[D(+N)] string` when the encoding's `varData` has `characterEncoding`, else `[D(N)] Binary[,,]`; `N` = declared `maxValue` / type width, capped at 1 048 576 with `[DeclaredMaxLength(n)]` |
| `<type primitiveType>` plain                              | inlined primitive: `char`, `int8`→`sbyte`, `uint8`→`byte`, `int16`→`short`, `uint16`→`ushort`, `int32`→`int`, `uint32`→`uint`, `int64`→`long`, `uint64`→`ulong`, `float`, `double` |
| `<type>` with constraints                                 | TYPEDEF class: `length>1` → `[D(+N)] string` (char) / `[D(N)] T[]` (`uint8[]` → `Binary[]`); **`minValue`+`maxValue` → `[MinMax(a, b)]`** so AdHoc bit-packs the hard range; optional/`nullValue` → `T?` + `[NullValue]`; `characterEncoding`, `semanticType` → attributes |
| `<type presence="constant">value</type>`                  | `const T name = value;` (`char` with several characters → `const string`)                                 |
| `<composite>`                                             | class with its members in order; `<ref>` resolves to typedef / composite / enum / set; enums, sets and composites declared *inside* a composite are hoisted to project level (a field and a nested type cannot share a name) |
| `<enum encodingType>`                                     | `enum Name { X = 'c' }` for char encodings, numeric otherwise; `: long` when needed; < 2 values → `struct` constants container |
| `<set encodingType>`                                      | `[Flags] enum Name { choice = 1 << bit }` (`: long` / `: ulong` for high bits)                            |
| `<xi:include href>`                                       | the included `<types>` are merged (resolved relative to the schema file)                                  |
| `offset`, `byteOrder`, `blockLength`                      | ignored / header comment; `blockLength` kept as `[BlockLength(n)]` on messages and groups                  |

## QuickFIX mapping

| QuickFIX                                                  | AdHoc                                                                                                     |
|:----------------------------------------------------------|:----------------------------------------------------------------------------------------------------------|
| `<message name msgtype msgcat>`                           | `class Name { const string MSG_TYPE; const string MSG_CAT; Header header; …; Trailer trailer; }`; the MsgType stays metadata and the Dashboard carries no id |
| `<header>` / `<trailer>`                                  | plain packs `Header` / `Trailer` embedded in every message; not `HeaderFor<>` because FIX header fields are strings and optional, which AdHoc headers do not allow. FIX 5 dictionaries declare them empty (FIXT carries the session layer): then nothing is emitted or embedded |
| `<field name required>`                                   | field with `[Tag(n)]`; `required='N'` → `T?` for value types                                              |
| field types                                               | INT / DAYOFMONTH / TAGNUM → `int`; FLOAT / PRICE / QTY / AMT / PERCENTAGE / PRICEOFFSET → `double`; CHAR → `char`; BOOLEAN → `bool`; DATA / XMLDATA → `[D(65535)] Binary[,,]`; all remaining types → `string`. Every type that is not INT / STRING / CHAR / BOOLEAN also gets `[FixType("…")]` so the original type survives |
| **`UTCTIMESTAMP`, `UTCDATEONLY`, `UTCDATE`, `UTCTIMEONLY`** | **`DateTime`** — these are absolute instants and AdHoc models time natively, so the concept is mapped rather than described by a string plus an attribute (and no `[FixType]` is emitted). `LOCALMKTDATE`, `MONTHYEAR`, `LOCALMKTTIME`, `TZTIMEONLY` and `TZTIMESTAMP` stay `string` + `[FixType]`: they carry no UTC instant — a local market date, a month-year coupon period or a local time with an offset denote a *calendar* or *market* value whose meaning depends on the venue's timezone, so forcing them into `DateTime` would invent an instant the wire never carried |
| **`SEQNUM`, `NUMINGROUP`, `LENGTH`**                      | **`[A] long` / `[A] int`** — FIX defines all three as counters with a floor and no ceiling, which is a claim about the *values*, not about how tag-value FIX happens to spell them. The wire carries the distance from the floor. **Check the arithmetic for your session** (see below) |
| `<field>` with `<value enum description/>`                | `enum FieldName { DESCRIPTION = code }` when all codes are integers (INT-like types) or single characters (CHAR / STRING); otherwise `struct FieldNameValues { const string DESCRIPTION = "code"; }` and the field keeps its C# type with `[Values("FieldNameValues")]`. BOOLEAN value sets (Y/N) are dropped; duplicate codes keep the first description |
| `<component name>`                                        | project-level class; referenced as a field named after the component (first letter lowered). Components without members are skipped (`MsgTypeGrp` in FIXT 1.1) |
| `<group name>`                                            | nested `class NameGroup` inside the container + `[Tag(NoXXX)] NameGroup[,,] Name;`, sized by the file's `_DefaultMaxLengthOf` |

## The `[A]` caveat — and it bites sequence numbers

Varint earns its keep only while the number actually sent stays small. The distance from the declared base
decides the width: 0…127 costs one byte, up to 16 383 two, up to 2 097 151 three, up to 268 435 455 four, and
beyond that five — where a plain 32-bit field would have cost four. So `[A]` **wins while the typical distance
from the base stays under about two million, and always loses past 268 435 455.**

`NUMINGROUP` and `LENGTH` sit far below that ceiling and are safe. **`SEQNUM` is not.** A FIX sequence number is
monotonic and resets only when the session does, so a busy session climbs past 2²⁸ within a day; from that point
`[A]` costs a fifth byte on **every message, forever**. Before shipping a high-volume session, reconsider the
attribute on `MsgSeqNum`, `LastMsgSeqNumProcessed`, `NextExpectedMsgSeqNum` and `RefSeqNum`: either drop `[A]`
and keep a fixed-width `long`, or give it a base that tracks the session (`[A(min)]`) if your sequence resets
predictably. On a session that resets daily and never passes a few million messages, `[A]` is a clear win.

Nothing about how SBE or tag-value FIX stores these numbers enters this decision — AdHoc lays out its own frame.
What decides it is where the values sit.

## Varint candidates left to the reader

Neither dialect states the physics of most numbers, but names, descriptions and one-sided bounds often imply it.
Where the converter sees such a hint on an integer wider than one byte, it emits a comment **on the field** —
never an invented attribute, because the choice needs traffic knowledge the converter does not have:

```csharp
// physics: a quantity — non-negative and clustered low → consider [A]
[Tag(5818), SemanticType("Qty")] Int32NULL? DecayQuantity;
```

The hints are: a name starting `Num`/`No…` or a description beginning "number of" → a count, `[A]`; a name ending
`Qty`, `Quantity`, `Size`, `Volume` → a quantity, `[A]`; a name ending `Px` or `Price` → values clustered around
the instrument's level, `[X(amplitude, level)]`; and an SBE `<type>` declaring a `minValue` with no `maxValue` →
`[A(min)]`. 90 such comments are emitted across the samples (46 in FixBinary, 23 in FIX50SP2, 10 in FIX44, 7 in
FIX42, 4 in fix_message_samples); none of the shipped schemas declares a floor-only `<type>`, so that last rule
never fires here.

## Collection defaults

Every generated file opens with

```csharp
enum _DefaultMaxLengthOf { Arrays = 65_535, Maps = 65_535, Sets = 65_535, Strings = 65_535, }
```

AdHoc defaults collections to 255 items. FIX payloads routinely exceed that (option chains, market-data entry
lists, security lists), and an over-long value is a protocol error rather than a truncation, so the permissive
value is the safer starting point. Tighten it per field with `[D(+N)]` / `[D(N)]` once the real bounds are known.

## Validation result

`./validate.sh AdHoc` on 2026-09-22 — every file `OK` (exit 0, no ERR/WRN):

The message count of every descriptor equals the number of messages in its source schema.

| Descriptor                | Messages | Packs (classes, attribute declarations excluded) | Enums | `[Flags]` enums | Value containers |
|:--------------------------|---------:|-------------------------------------------------:|------:|----------------:|-----------------:|
| FIX42                     |       46 |                                               86 |    82 |               0 |                7 |
| FIX44                     |       93 |                                              292 |   202 |               0 |               19 |
| FIX50SP2                  |      156 |                                            1 442 |   591 |               0 |               46 |
| FIXT11                    |        8 |                                               13 |     4 |               0 |                0 |
| FixBinary                 |       29 |                                               77 |    18 |               3 |                0 |
| fix_message_samples       |        6 |                                               22 |    17 |               2 |                4 |
| example_schema            |        1 |                                               15 |     4 |               1 |                0 |
| example_extension_schema  |        1 |                                               17 |     4 |               1 |                0 |
| car                       |        1 |                                               11 |     3 |               1 |                0 |
| basic_types_schema        |        2 |                                                4 |     3 |               1 |                0 |
| composite_elements_schema |        1 |                                                4 |     4 |               1 |                0 |
| group_with_data_schema    |        4 |                                                8 |     0 |               0 |                0 |

AdHoc idioms used, counted over the generated files: **0** pinned Dashboard ids (the agent assigns every pack id,
as the `AdHoc/*.branches.txt` dumps show), **382** `DateTime` fields converted from FIX UTC types (221 in
FIX50SP2, 107 in FIX44, 50 in FIX42, 4 in FIXT11), **495** `[A]` varint declarations on SEQNUM / NUMINGROUP /
LENGTH counters, **4** `[MinMax]` ranges from SBE `minValue`+`maxValue`, and one `_DefaultMaxLengthOf` per file.
The agent's `INF … appears to be a flags enum` lines are informational only.

## Limitations

- SBE: `offset`, `byteOrder`, `alignment`, `epoch` / `timeUnit` and schema-level `headerType` are not represented;
  SBE 2.0 rc constructs beyond the 1.0 sample set (e.g. `<enum>` referencing another schema) are untested.
  Var-data caps above 1 048 576 items are reduced (the declared value is kept in `[DeclaredMaxLength]`).
- QuickFIX: the FIX header/trailer are plain sub-packs, not AdHoc headers; DATA fields are capped at 65 535 bytes;
  the message set is bidirectional (FIX does not tie a message to a side). FIX Orchestra files are not converted.
  `LOCALMKTDATE`, `MONTHYEAR`, `LOCALMKTTIME`, `TZTIMEONLY` and `TZTIMESTAMP` remain strings, as explained in the
  mapping table; a venue that knows its timezone can turn them into a `DateTimeDef` alias by hand.
- Both: enums with fewer than two members become constants containers because AdHoc rejects single-value enums.
  Source identity (SBE template id, FIX MsgType, field tags, `sinceVersion`, block lengths) is kept only as
  `const` values and attributes — AdHoc assigns its own pack ids and lays out its own wire.
- A constant whose SBE encoding is `int8` / `int16` is emitted as `const int`: AdHocAgent throws an
  InvalidCastException on `const sbyte` / `const short`. Fields keep their exact `sbyte` / `short` type, and the
  encoding is still recorded by the field's own attributes, so nothing on the wire changes.
