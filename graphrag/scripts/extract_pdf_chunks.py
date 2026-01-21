#!/usr/bin/env python3
"""
PDF to Chunk TSV Converter for GraphRAG Pipeline

This script extracts text from PDFs in the MSRCorpus and generates
a TSV file suitable for the GraphRAG Flink pipeline.

Usage:
    python scripts/extract_pdf_chunks.py --input /path/to/pdfs --output data/msr_chunks.tsv [--limit N]
"""

import os
import sys
import hashlib
import argparse
from pathlib import Path
from datetime import datetime

# Add pdfminer
try:
    from pdfminer.high_level import extract_text
    from pdfminer.pdfparser import PDFSyntaxError
except ImportError:
    print("Error: pdfminer.six not installed. Run: pip install pdfminer.six")
    sys.exit(1)


def sha256(text: str) -> str:
    """Compute SHA-256 hash of text."""
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def normalize_text(text: str) -> str:
    """Normalize text by cleaning whitespace."""
    return ' '.join(text.split())


def extract_pdf_text(pdf_path: str) -> str:
    """Extract text from a PDF file."""
    try:
        text = extract_text(pdf_path)
        return normalize_text(text) if text else ""
    except PDFSyntaxError:
        print(f"  Warning: Could not parse PDF: {pdf_path}")
        return ""
    except Exception as e:
        print(f"  Warning: Error extracting {pdf_path}: {e}")
        return ""


def chunk_text(text: str, chunk_size: int = 1000, overlap: int = 100) -> list:
    """
    Split text into overlapping chunks.
    
    Returns list of (chunk_text, start_pos, end_pos)
    """
    if len(text) <= chunk_size:
        return [(text, 0, len(text))]
    
    chunks = []
    start = 0
    
    while start < len(text):
        end = min(start + chunk_size, len(text))
        
        # Try to break at sentence boundary
        if end < len(text):
            # Look for sentence end (.!?) followed by space
            for i in range(end, max(start + chunk_size // 2, start), -1):
                if text[i-1] in '.!?' and (i >= len(text) or text[i].isspace()):
                    end = i
                    break
            else:
                # Fall back to word boundary
                for i in range(end, max(start + chunk_size // 2, start), -1):
                    if text[i-1].isspace():
                        end = i
                        break
        
        chunk_text = text[start:end].strip()
        if chunk_text:
            chunks.append((chunk_text, start, end))
        
        # Move start, accounting for overlap
        start = end - overlap
        if start <= chunks[-1][1] if chunks else 0:
            start = end  # Prevent infinite loop
    
    return chunks


def process_pdf(pdf_path: str, chunk_size: int, overlap: int) -> list:
    """
    Process a PDF and return list of chunk records.
    
    Each record is a dict with all fields needed for the TSV.
    """
    pdf_name = os.path.basename(pdf_path)
    doc_id = f"doc:{sha256(pdf_path)[:16]}"
    source_uri = f"file://{pdf_path}"
    
    # Extract text
    text = extract_pdf_text(pdf_path)
    if not text:
        return []
    
    # Generate chunks
    chunks = chunk_text(text, chunk_size, overlap)
    
    records = []
    for idx, (text_segment, start_pos, end_pos) in enumerate(chunks):
        content_hash = f"hash:{sha256(text_segment)[:16]}"
        chunk_id = f"chunk:{sha256(f'{doc_id}:{start_pos}:{end_pos}:{content_hash}')[:16]}"
        
        records.append({
            'chunkId': chunk_id,
            'docId': doc_id,
            'chunkIx': idx,
            'startPos': start_pos,
            'endPos': end_pos,
            'sectionPath': f"{pdf_name.replace('.pdf', '')}/chunk_{idx}",
            'text': text_segment.replace('\t', ' ').replace('\n', ' '),
            'sourceUri': source_uri,
            'contentHash': content_hash,
            'language': 'en'
        })
    
    return records


def write_tsv(records: list, output_path: str):
    """Write records to TSV file."""
    with open(output_path, 'w', encoding='utf-8') as f:
        # Write header comment
        f.write("# GraphRAG Chunk File - Generated from MSRCorpus PDFs\n")
        f.write(f"# Generated: {datetime.now().isoformat()}\n")
        f.write("# Format: chunkId\\tdocId\\tchunkIx\\tstartPos\\tendPos\\tsectionPath\\ttext\\tsourceUri\\tcontentHash\\tlanguage\n")
        
        for record in records:
            line = '\t'.join([
                record['chunkId'],
                record['docId'],
                str(record['chunkIx']),
                str(record['startPos']),
                str(record['endPos']),
                record['sectionPath'],
                record['text'],
                record['sourceUri'],
                record['contentHash'],
                record['language']
            ])
            f.write(line + '\n')


def main():
    parser = argparse.ArgumentParser(description='Extract chunks from MSRCorpus PDFs')
    parser.add_argument('--input', '-i', required=True, help='Input directory containing PDFs')
    parser.add_argument('--output', '-o', default='data/msr_chunks.tsv', help='Output TSV file')
    parser.add_argument('--limit', '-l', type=int, default=0, help='Limit number of PDFs to process (0 = all)')
    parser.add_argument('--chunk-size', type=int, default=1000, help='Chunk size in characters')
    parser.add_argument('--overlap', type=int, default=100, help='Overlap between chunks')
    args = parser.parse_args()
    
    # Find PDFs
    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Error: Input directory does not exist: {args.input}")
        sys.exit(1)
    
    pdf_files = sorted(input_path.glob('*.pdf'))
    if not pdf_files:
        print(f"Error: No PDF files found in {args.input}")
        sys.exit(1)
    
    # Apply limit
    if args.limit > 0:
        pdf_files = pdf_files[:args.limit]
    
    print(f"=" * 60)
    print(f"MSRCorpus PDF to Chunk Converter")
    print(f"=" * 60)
    print(f"Input directory: {args.input}")
    print(f"Output file: {args.output}")
    print(f"PDFs to process: {len(pdf_files)}")
    print(f"Chunk size: {args.chunk_size} chars")
    print(f"Overlap: {args.overlap} chars")
    print(f"=" * 60)
    
    # Process PDFs
    all_records = []
    for idx, pdf_path in enumerate(pdf_files, 1):
        print(f"[{idx}/{len(pdf_files)}] Processing: {pdf_path.name}")
        records = process_pdf(str(pdf_path), args.chunk_size, args.overlap)
        all_records.extend(records)
        print(f"  -> {len(records)} chunks")
    
    # Write output
    os.makedirs(os.path.dirname(args.output) or '.', exist_ok=True)
    write_tsv(all_records, args.output)
    
    print(f"=" * 60)
    print(f"Done!")
    print(f"Total PDFs processed: {len(pdf_files)}")
    print(f"Total chunks generated: {len(all_records)}")
    print(f"Output written to: {args.output}")
    print(f"=" * 60)


if __name__ == '__main__':
    main()

