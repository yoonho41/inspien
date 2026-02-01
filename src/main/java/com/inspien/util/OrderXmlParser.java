package com.inspien.util;

import com.inspien.dto.OrderHeaderDTO;
import com.inspien.dto.OrderItemDTO;

import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class OrderXmlParser {

    public record Parsed(List<OrderHeaderDTO> headers, List<OrderItemDTO> items) {}

    public Parsed parse(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) {
            throw new IllegalArgumentException("XML body is empty.");
        }

        String trimmed = rawXml.trim();

        try {
            Document doc;

            try {
                doc = parseDocument(trimmed);
            } catch (Exception first) {
                String wrapped = wrapXml(trimmed);
                try {
                    doc = parseDocument(wrapped);
                } catch (Exception second) {
                    throw new IllegalArgumentException(
                        "XML parsing failed: " + second.getMessage(),
                        second
                    );
                }
            }

            List<OrderHeaderDTO> headers = new ArrayList<>();
            NodeList headerNodes = doc.getElementsByTagName("HEADER");
            for (int i = 0; i < headerNodes.getLength(); i++) {
                Element e = (Element) headerNodes.item(i);
                OrderHeaderDTO h = new OrderHeaderDTO();
                h.setUserId(text(e, "USER_ID"));
                h.setName(text(e, "NAME"));
                h.setAddress(text(e, "ADDRESS"));
                h.setStatus(text(e, "STATUS"));
                headers.add(h);
            }

            List<OrderItemDTO> items = new ArrayList<>();
            NodeList itemNodes = doc.getElementsByTagName("ITEM");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element e = (Element) itemNodes.item(i);
                OrderItemDTO it = new OrderItemDTO();
                it.setUserId(text(e, "USER_ID"));
                it.setItemId(text(e, "ITEM_ID"));
                it.setItemName(text(e, "ITEM_NAME"));
                it.setPrice(text(e, "PRICE"));
                items.add(it);
            }

            return new Parsed(headers, items);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("XML parsing failed: " + e.getMessage(), e);
        }
    }

    private Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        // XML External Entity Injection 방지
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setExpandEntityReferences(false);

        return dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String wrapXml(String xml) {
        String trimmed = xml.trim();
        if (trimmed.startsWith("<?xml")) {
            int end = trimmed.indexOf("?>");
            if (end != -1) {
                String decl = trimmed.substring(0, end + 2);
                String rest = trimmed.substring(end + 2);
                return decl + "<ROOT>" + rest + "</ROOT>";
            }
        }
        return "<ROOT>" + xml + "</ROOT>";
    }

    private String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) return null;

        String v = list.item(0).getTextContent();
        return v == null ? null : v.trim();
    }
}
