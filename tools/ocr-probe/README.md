# OCR probe

Runs a collateral document through the portal's reader from the command line and prints
what the upload endpoint would decide about it.

It exists because testing the reader through the app means a login, an account with no open
loan application, an amount above the collateral threshold and a form — four things that have
nothing to do with whether a land title can be read.

## Use

```sh
(cd ../../backend && ./mvnw -q -DskipTests compile)   # once, and after backend changes

./probe.sh ~/Desktop/title.jpg            # try it as every kind, to see what it looks like
./probe.sh LAND ~/Desktop/title.jpg       # try it as one kind
./probe.sh LAND a.jpg b.jpg c.pdf         # several at once
```

It prints, per kind: the verdict the customer would get, which marker words were found, each
field read or not read, and the full text the reader produced.

## What it is not

It is not a test suite and it asserts nothing. It is a way of looking at what the reader
actually does with a real document, which is the only thing that settles whether the patterns
are right.

It compiles against `backend/target/classes` and calls `TesseractReader`, `FieldReader`,
`CollateralKind.looksLikeThisDocument` and `CollateralService.refuseIfNotTheRightDocument`
directly. There is no second copy of the rules here, deliberately: a probe that reimplements
what it is probing agrees with itself and tells you nothing.

Nothing in this folder is on the backend's build path, so it ships with nothing.
