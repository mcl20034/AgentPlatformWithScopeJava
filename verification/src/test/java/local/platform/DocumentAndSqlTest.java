package local.platform;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentAndSqlTest {
    @TempDir Path temp;
    @Test void sqlParserFindsTablesBehindCteSubqueryAndUnion() throws Exception {
        String query = "WITH q AS (SELECT region, amount FROM sales.orders) SELECT region, SUM(amount) FROM q GROUP BY region UNION ALL SELECT region, amount FROM sales.refunds WHERE order_id IN (SELECT id FROM secret.payments)";
        var statement = CCJSqlParserUtil.parse(query);
        assertThat(statement).isInstanceOf(Select.class);
        Set<String> tables = new TablesNamesFinder().getTables(statement);
        assertThat(tables).contains("sales.orders", "sales.refunds", "secret.payments").doesNotContain("q");
        assertThat(tables.stream().allMatch(Set.of("sales.orders","sales.refunds")::contains)).isFalse();
    }
    @Test void sqlParserDistinguishesMultipleStatementsAndWriteCommands() throws Exception {
        var multiple=CCJSqlParserUtil.parseStatements("SELECT * FROM orders; DELETE FROM orders");
        assertThat(multiple.getStatements()).hasSize(2);
        assertThat(multiple.getStatements().get(1)).isNotInstanceOf(Select.class);
        // AST parsing alone is deliberately NOT treated as a safe-query validator.
        assertThat(CCJSqlParserUtil.parse("SELECT SLEEP(10)")).isInstanceOf(Select.class);
    }
    @Test void chinesePdfTextCanBeLocatedByPage() throws Exception {
        Path font=Path.of(System.getProperty("probe.font", "C:/Windows/Fonts/simhei.ttf"));
        assumeTrue(Files.isRegularFile(font), "Set -Dprobe.font to a CJK TrueType font");
        Path file=temp.resolve("sample.pdf");
        try (PDDocument document=new PDDocument()) {
            var typeface=PDType0Font.load(document,font.toFile());
            for(String text:List.of("第一章：销售口径", "第二章：退款处理")) {
                PDPage page=new PDPage(); document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.beginText();content.setFont(typeface,14);content.newLineAtOffset(50,700);content.showText(text);content.endText();
                }
            }
            document.save(file.toFile());
        }
        try(PDDocument document=Loader.loadPDF(file.toFile())) {
            var stripper=new PDFTextStripper();stripper.setStartPage(2);stripper.setEndPage(2);
            assertThat(stripper.getText(document)).contains("退款处理").doesNotContain("销售口径");
        }
    }
    @Test void docxPreservesChineseParagraphAndTableBodyOrder() throws Exception {
        Path file=temp.resolve("sample.docx");
        try(var document=new XWPFDocument()) {
            document.createParagraph().createRun().setText("销售额定义");
            document.createTable(1,2).getRow(0).getCell(0).setText("扣除退款");
            document.createParagraph().createRun().setText("统计日期使用支付时间");
            try(var output=Files.newOutputStream(file)){document.write(output);}
        }
        try(var document=new XWPFDocument(Files.newInputStream(file))) {
            assertThat(document.getBodyElements()).hasSize(3);
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo("销售额定义");
            assertThat(document.getTables().get(0).getRow(0).getCell(0).getText()).isEqualTo("扣除退款");
        }
    }
    @Test void txtLinePositionsAndUtf8RoundTrip() throws Exception {
        Path file=temp.resolve("sample.txt");
        Files.writeString(file,"第一行\n退款规则\n末行",StandardCharsets.UTF_8);
        assertThat(Files.readAllLines(file,StandardCharsets.UTF_8)).containsExactly("第一行","退款规则","末行");
    }
    @Test void damagedPdfFailsRatherThanBecomingSearchable() throws Exception {
        Path file=temp.resolve("damaged.pdf");Files.writeString(file,"%PDF-1.7 broken fixture");
        assertThatThrownBy(()->Loader.loadPDF(file.toFile())).isInstanceOf(java.io.IOException.class);
    }
}
