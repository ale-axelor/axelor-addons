/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.prestashop.exports.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.axelor.apps.base.db.AppPrestashop;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.apps.prestashop.db.Countries;
import com.axelor.apps.prestashop.db.Language;
import com.axelor.apps.prestashop.db.LanguageDetails;
import com.axelor.apps.prestashop.db.Prestashop;
import com.axelor.apps.prestashop.entities.PrestashopResourceType;
import com.axelor.apps.prestashop.exception.IExceptionMessage;
import com.axelor.apps.prestashop.service.library.PSWebServiceClient;
import com.axelor.apps.prestashop.service.library.PSWebServiceClient.Options;
import com.axelor.apps.prestashop.service.library.PrestaShopWebserviceException;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

@Singleton
public class ExportCountryServiceImpl implements ExportCountryService {
	private CountryRepository countryRepo;

	@Inject
	public ExportCountryServiceImpl(CountryRepository countryRepo) {
		this.countryRepo = countryRepo;
	}

	/**
	 *  Check on prestashop country is already there
	 *
	 * @param countryCode unique code of country
	 * @return id of prestashop's country if it is.
	 * @throws PrestaShopWebserviceException
	 */
	public Integer getCountryId(AppPrestashop appConfig, String countryCode) throws PrestaShopWebserviceException {
		PSWebServiceClient ws = new PSWebServiceClient(appConfig.getPrestaShopUrl(), appConfig.getPrestaShopKey());
		HashMap<String, String> countryMap = new HashMap<String, String>();
		countryMap.put("iso_code", countryCode);
		Options options = new Options();
		options.setResourceType(PrestashopResourceType.COUNTRIES);
		options.setFilter(countryMap);
		Document str =  ws.get(options);

		NodeList list = str.getElementsByTagName("countries");
		for(int i = 0; i < list.getLength(); i++) {
		    Element element = (Element) list.item(i);
		    NodeList node = element.getElementsByTagName("country");
		    Node country = node.item(i);
		    if(node.getLength() > 0) {
		    	return Integer.valueOf(country.getAttributes().getNamedItem("id").getNodeValue());
		    }
		}
		return null;
	}

	@Override
	@Transactional
	public void exportCountry(AppPrestashop appConfig, ZonedDateTime endDate, BufferedWriter bwExport) throws IOException, PrestaShopWebserviceException, ParserConfigurationException, SAXException, TransformerException {
		int done = 0;
		int anomaly = 0;

		PSWebServiceClient ws;
		bwExport.newLine();
		bwExport.write("-----------------------------------------------");
		bwExport.newLine();
		bwExport.write("Country");
		List<Country> countries = null;
		Document document = null;
		String schema = null;

		if(endDate == null) {
			countries = countryRepo.all().fetch();
		} else {
			countries = countryRepo.all().filter("self.createdOn > ?1 OR self.updatedOn > ?2 OR self.prestaShopId = null", endDate, endDate).fetch();
		}

		for(Country countryObj : countries) {
			try {

				Integer prestaShopId = getCountryId(appConfig, countryObj.getAlpha2Code());

				if(countryObj.getName() == null) {
					throw new AxelorException(IException.NO_VALUE, I18n.get(IExceptionMessage.INVALID_COUNTRY));
				}

				LanguageDetails languageObj = new LanguageDetails();
				languageObj.setId("1");
				languageObj.setValue(countryObj.getName());

				Language language = new Language();
				language.setLanguage(languageObj);

				Countries country = new Countries();
				if(prestaShopId != null) {
					country.setId(prestaShopId.toString());
				} else {
					country.setId(Objects.toString(countryObj.getPrestaShopId(), null));
				}

				country.setName(language);
				country.setIso_code(countryObj.getAlpha2Code());
				country.setId_zone("1");
				country.setContains_states("0");
				country.setNeed_identification_number("0");
				country.setDisplay_tax_label("1");
				country.setActive("1");
				Prestashop prestaShop = new Prestashop();
				prestaShop.setPrestashop(country);

				StringWriter sw = new StringWriter();
				JAXBContext contextObj = JAXBContext.newInstance(Prestashop.class);
				Marshaller marshallerObj = contextObj.createMarshaller();
				marshallerObj.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
				marshallerObj.marshal(prestaShop, sw);
				schema = sw.toString();

				Options options = new Options();
				options.setResourceType(PrestashopResourceType.COUNTRIES);
				options.setXmlPayload(schema);

				if (countryObj.getPrestaShopId() == null && prestaShopId == null) {
					ws = new PSWebServiceClient(appConfig.getPrestaShopUrl() + "/api/countries?schema=synopsis", appConfig.getPrestaShopKey());
					document = ws.add(options);
				} else if (prestaShopId != null){
					options.setRequestedId(prestaShopId);
					ws = new PSWebServiceClient(appConfig.getPrestaShopUrl(), appConfig.getPrestaShopKey());
					document = ws.edit(options);

				} else {
					options.setRequestedId(countryObj.getPrestaShopId());
					ws = new PSWebServiceClient(appConfig.getPrestaShopUrl(), appConfig.getPrestaShopKey());
					document = ws.edit(options);
				}

				countryObj.setPrestaShopId(Integer.valueOf(document.getElementsByTagName("id").item(0).getTextContent()));
				countryRepo.save(countryObj);
				done++;

			} catch (AxelorException e) {
				bwExport.newLine();
				bwExport.newLine();
				bwExport.write("Id - " + countryObj.getId().toString() + " " + e.getMessage());
				anomaly++;
				continue;

			} catch (Exception e) {

				bwExport.newLine();
				bwExport.newLine();
				bwExport.write("Id - " + countryObj.getId().toString() + " " + e.getMessage());
				anomaly++;
				continue;
			}
		}

		bwExport.newLine();
		bwExport.newLine();
		bwExport.write("Succeed : " + done + " " + "Anomaly : " + anomaly);
	}
}
