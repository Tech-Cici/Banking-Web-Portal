# OCR lab

A scratchpad for working out how to read Rwandan collateral documents — land leases,
vehicle registrations, company certificates — before changing the portal.

```sh
./lab.sh samples/title.pdf
```

Drop documents in `samples/`. It is gitignored: these are real people's titles and ID
numbers, and they do not belong in the repository.

## What it does

Reads the same page several ways and shows you which works:

- five preprocessings (as uploaded, grayscale, doubled, tripled, doubled with better
  interpolation)
- two page-segmentation modes (6 = treat the page as one block, 3 = detect the layout)

For each combination it prints how much text came out and which identifiers matched, then
picks the best, says which document kind it looks like, and prints the full reading.

That comparison is the point. The portal's reader has to choose one setting and live with
it; this one can try them all against a document in your hand and tell you which to choose.

## The thing to be careful about

**The patterns in `Lab.java` are a SECOND COPY.** The real ones live in
`backend/src/main/java/rw/bank/ibanking/collateral/FieldReader.java`, and the real
preprocessing in `TesseractReader.java`.

Nothing here changes the portal. When something works, port it across by hand — and until
you do, the two disagree. That duplication is the price of a sandbox and it is only worth
paying while you are still working out what the rules should be.

## PDFs

Taken directly. Every page is rendered at 300 DPI in greyscale — the same library and the
same settings the portal uses, borrowed from the backend's dependencies.

That is on purpose. How a page becomes pixels decides most of what a reader can see, so
converting PDFs some other way here would mean tuning a conversion that never runs in
production. The first run asks Maven to resolve the backend's classpath and caches it.

The portal reads only the first three pages of a PDF. The lab reads all of them and says so,
because when a title runs to four pages you want to know which one carries the parcel number
before deciding whether that limit is wrong.

## Limits

HEIC will not open — Java cannot decode it — and the lab says so rather than pretending the
page was blank. That is the same refusal the portal gives, for the same reason.
