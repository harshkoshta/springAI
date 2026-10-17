import json
import sys
from pathlib import Path
from collections import defaultdict

from docling.document_converter import DocumentConverter
from docling.chunking import HybridChunker

if len(sys.argv) != 2:
    raise SystemExit("Usage: python pdf_chunker.py <pdf-path>")

source = Path(sys.argv[1]).resolve()

if not source.is_file():
    raise SystemExit(f"PDF does not exist: {source}")

document_id = Path(source).name

# 1. Convert PDF
converter = DocumentConverter()
doc = converter.convert(source).document

# 2. Generate chunks
chunker = HybridChunker(max_tokens=500)
chunks = list(chunker.chunk(doc))

# 3. Group chunks by primary page
page_chunks = defaultdict(list)

for chunk in chunks:

    # Extract page numbers BEFORE removing provenance
    pages = sorted({
        prov.page_no
        for item in chunk.meta.doc_items
        for prov in item.prov
    })

    page_id = pages[0] if pages else None

    # Preserve original metadata, but remove layout details
    metadata = chunk.meta.model_dump(mode="json")

    for item in metadata.get("doc_items", []):
        item.pop("prov", None)

    chunk_data = {
        "text": chunker.contextualize(chunk),
        "rawText": chunk.text,
        "headings": chunk.meta.headings or [],
        "pageNumbers": pages,
        "elementIds": [
            item.self_ref
            for item in chunk.meta.doc_items
        ],
        "metadata": metadata
    }

    page_chunks[page_id].append(chunk_data)

# 4. Assign chunk IDs starting from 0 on each page
output_chunks = []

for page_id in sorted(page_chunks, key=lambda p: (p is None, p or 0)):

    page_items = page_chunks[page_id]
    total_on_page = len(page_items)

    for chunk_index, chunk_data in enumerate(page_items):

        page_part = f"p{page_id}" if page_id is not None else "punknown"

        output_chunks.append({
            "chunkId": f"{document_id}-{page_part}-c{chunk_index}",
            "documentId": document_id,
            "pageId": page_id,
            "chunkIndex": chunk_index,
            "totalChunksOnPage": total_on_page,
            **chunk_data
        })

# 5. Final JSON
output = {
    "documentId": document_id,
    "fileName": Path(source).name,
    "totalPages": len(doc.pages),
    "totalChunks": len(output_chunks),
    "chunksPerPage": {
        str(page): len(page_chunks.get(page, []))
        for page in range(1, len(doc.pages) + 1)
    },
    "chunks": output_chunks
}

# 6. Save
output_path = source.with_suffix(".json")

with output_path.open("w", encoding="utf-8") as f:
    json.dump(output, f, indent=2, ensure_ascii=False)

print("Total pages:", output["totalPages"])
print("Total chunks:", output["totalChunks"])

for page, count in output["chunksPerPage"].items():
    print(f"Page {page}: {count} chunks")

print("Saved:", output_path)
