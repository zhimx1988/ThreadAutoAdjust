//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v1.0.5-b16-fcs 
// 	See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// 	Any modifications to this file will be lost upon recompilation of the source schema. 
// 	Generated on: 2009.11.26 在 04:48:36 CST 
//


package com.onceas.descriptor.wm;


/**
 * Java content class for context-case element declaration.
 * 	<p>The following schema fragment specifies the expected 	content contained within this java content object. 	(defined at file:/E:/wm_1_0.xsd line 126)
 * <p>
 * <pre>
 * &lt;element name="context-case">
 *   &lt;complexType>
 *     &lt;complexContent>
 *       &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *         &lt;sequence>
 *           &lt;choice>
 *             &lt;element ref="{http://www.ios.ac.cn/onceas}user-name"/>
 *             &lt;element ref="{http://www.ios.ac.cn/onceas}group-name"/>
 *           &lt;/choice>
 *           &lt;element ref="{http://www.ios.ac.cn/onceas}request-class-name"/>
 *         &lt;/sequence>
 *       &lt;/restriction>
 *     &lt;/complexContent>
 *   &lt;/complexType>
 * &lt;/element>
 * </pre>
 * 
 */
public interface ContextCase
    extends javax.xml.bind.Element, com.onceas.descriptor.wm.ContextCaseType
{


}