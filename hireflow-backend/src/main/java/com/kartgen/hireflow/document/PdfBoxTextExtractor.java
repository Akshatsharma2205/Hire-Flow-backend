package com.kartgen.hireflow.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PdfBoxTextExtractor {

    public String extractText(byte[] pdfData) {
        try (PDDocument doc = Loader.loadPDF(pdfData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        } catch (Exception e) {
            log.warn("PDFBox text extraction failed: {}", e.getMessage());
            return "";
        }
    }

    public int getPageCount(byte[] pdfData) {
        try (PDDocument doc = Loader.loadPDF(pdfData)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return 0;
        }
    }
}

