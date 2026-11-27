package com.gamagerecruiters.marketplace.modules.payment.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;

/**
 * Generates customer payment receipts from authorized, verified payment data.
 *
 * <p>Documents are generated on demand without storing PDF files or
 * exposing internal gateway and provider financial information.</p>
 */
@Service
public class PaymentReceiptPdfService {

    private static final Color TEAL = new Color(15, 118, 110);
    private static final Color DARK = new Color(30, 41, 59);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color LIGHT_TEAL = new Color(240, 253, 250);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

    private final PaymentReceiptDetailsService receiptDetailsService;

    public PaymentReceiptPdfService(
            PaymentReceiptDetailsService receiptDetailsService) {
        this.receiptDetailsService =
                Objects.requireNonNull(receiptDetailsService);
    }

    /**
     * Generates a PDF after verifying customer ownership and receipt eligibility.
     *
     * @param paymentId associated payment identifier
     * @param customerId authenticated customer identifier
     * @return generated PDF document bytes
     */
    public byte[] generateForCustomer(
            String paymentId,
            String customerId) {

        PaymentReceiptDetailsResponse details =
                receiptDetailsService.getDetailsForCustomer(
                        paymentId, customerId);

        PaymentReceiptResponse receipt = details.receipt();

        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            document.getDocumentInformation()
                    .setTitle("LocalServe Payment Receipt");
            document.getDocumentInformation()
                    .setAuthor("LocalServe");

            PDType1Font regular = new PDType1Font(
                    Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(
                    Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream stream =
                    new PDPageContentStream(document, page)) {

                float width = page.getMediaBox().getWidth();

                fill(stream, TEAL, 0, 830, width, 12);

                text(stream, bold, 25, TEAL,
                        42, 782, "LocalServe");

                text(stream, regular, 11, MUTED,
                        42, 762, "Verified payment receipt");

                text(stream, bold, 22, DARK,
                        42, 715, "Payment Receipt");

                fill(stream, LIGHT_TEAL, 435, 704, 118, 30);
                text(stream, bold, 10, TEAL,
                        448, 715, "PAYMENT VERIFIED");

                field(stream, regular, bold,
                        680, "Receipt reference",
                        receipt.receiptReference());

                field(stream, regular, bold,
                        640, "Payment date",
                        DATE_FORMAT.format(receipt.paidAt()));

                line(stream, 610);

                text(stream, bold, 14, DARK,
                        42, 585, "Booking and Payment Details");

                field(stream, regular, bold,
                        555, "Booking reference",
                        details.bookingReference());

                field(stream, regular, bold,
                        515, "Booking ID",
                        receipt.bookingId());

                field(stream, regular, bold,
                        475, "Payment ID",
                        receipt.paymentId());

                field(stream, regular, bold,
                        435, "Provider ID",
                        details.providerId());

                line(stream, 405);

                text(stream, bold, 14, DARK,
                        42, 380, "Payment Summary");

                fill(stream, BORDER, 42, 337, 511, 26);

                text(stream, bold, 10, DARK,
                        54, 346, "Description");

                rightText(stream, bold, 10, DARK,
                        541, 346, "Amount");

                String amount = receipt.currency() + " "
                        + receipt.amount().toPlainString();

                text(stream, regular, 11, DARK,
                        54, 312, "Verified booking payment");

                rightText(stream, regular, 11, DARK,
                        541, 312, amount);

                line(stream, 290);

                fill(stream, LIGHT_TEAL, 42, 230, 511, 45);

                text(stream, bold, 14, TEAL,
                        54, 247, "Total Paid");

                rightText(stream, bold, 16, TEAL,
                        541, 247, amount);

                text(stream, regular, 10, MUTED,
                        42, 195,
                        "This receipt records the original verified payment.");

                text(stream, regular, 10, MUTED,
                        42, 180,
                        "Subsequent refunds are recorded separately.");

                line(stream, 100);

                text(stream, bold, 11, TEAL,
                        42, 78, "LocalServe");

                text(stream, regular, 9, MUTED,
                        42, 62,
                        "System-generated payment receipt.");
            }

            document.save(output);
            return output.toByteArray();

        } catch (IOException | IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "RECEIPT_GENERATION_FAILED",
                    "Unable to generate payment receipt");
        }
    }

    private static void field(
            PDPageContentStream stream,
            PDType1Font regular,
            PDType1Font bold,
            float y,
            String label,
            String value) throws IOException {

        text(stream, regular, 10, MUTED, 42, y, label);

        text(stream, bold, 11, DARK, 42, y - 16,
                value == null || value.isBlank()
                        ? "Not available" : value);
    }

    private static void text(
            PDPageContentStream stream,
            PDType1Font font,
            float size,
            Color color,
            float x,
            float y,
            String value) throws IOException {

        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static void rightText(
            PDPageContentStream stream,
            PDType1Font font,
            float size,
            Color color,
            float right,
            float y,
            String value) throws IOException {

        float textWidth =
                font.getStringWidth(value) / 1000f * size;

        text(stream, font, size, color,
                right - textWidth, y, value);
    }

    private static void fill(
            PDPageContentStream stream,
            Color color,
            float x,
            float y,
            float width,
            float height) throws IOException {

        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private static void line(
            PDPageContentStream stream,
            float y) throws IOException {

        stream.setStrokingColor(BORDER);
        stream.moveTo(42, y);
        stream.lineTo(553, y);
        stream.stroke();
    }
}
