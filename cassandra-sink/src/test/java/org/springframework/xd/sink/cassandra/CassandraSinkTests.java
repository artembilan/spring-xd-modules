/*
 * Copyright 2015 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.sink.cassandra;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.xd.dirt.server.singlenode.SingleNodeApplication;
import org.springframework.xd.dirt.test.SingleNodeIntegrationTestSupport;
import org.springframework.xd.dirt.test.SingletonModuleRegistry;
import org.springframework.xd.dirt.test.process.SingleNodeProcessingChainProducer;
import org.springframework.xd.dirt.test.process.SingleNodeProcessingChainSupport;
import org.springframework.xd.module.ModuleType;
import org.springframework.xd.test.domain.Book;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.thrift.transport.TTransportException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import reactor.fn.Supplier;

/**
 * @author Artem Bilan
 */
public class CassandraSinkTests {

	private static final String STREAM_NAME = "cassandraTest";

	private static final String MODULE_NAME = "cassandra";

	private static final String CASSANDRA_CONFIG = "spring-cassandra.yaml";

	private static final int PORT = 9043; // See spring-cassandra.yaml - native_transport_port


	private static Cluster cluster;

	private static CassandraOperations cassandraTemplate;

	private static SingleNodeApplication application;

	@BeforeClass
	public static void setUp() throws ConfigurationException, IOException, TTransportException {
		EmbeddedCassandraServerHelper.startEmbeddedCassandra(CASSANDRA_CONFIG, "build/embeddedCassandra");
		cluster = Cluster.builder()
				.addContactPoint("localhost")
				.withPort(PORT)
				.build();

		cluster.connect().execute(String.format("CREATE KEYSPACE IF NOT EXISTS %s" +
				"  WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };", STREAM_NAME));

		cassandraTemplate = new CassandraTemplate(cluster.connect(STREAM_NAME));

		application = new SingleNodeApplication().run();
		SingleNodeIntegrationTestSupport integrationTest = new SingleNodeIntegrationTestSupport(application);
		integrationTest.addModuleRegistry(new SingletonModuleRegistry(ModuleType.sink, MODULE_NAME));
	}

	@AfterClass
	public static void cleanup() {
		if (cluster != null) {
			cluster.close();
		}
		EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
	}

	@Test
	@Ignore("Looks like there is a CL issue with sending mapped entity to the XD stream")
	public void testInsert() throws InterruptedException {
		String stream = String.format("%s --port=%s --schemaAction=%s --entityBasePackages=%s",
				MODULE_NAME, PORT, SchemaAction.RECREATE_DROP_UNUSED, Book.class.getPackage().getName());

		SingleNodeProcessingChainProducer chain =
				SingleNodeProcessingChainSupport.chainProducer(application, STREAM_NAME, stream);

		Book book = new Book();
		book.setIsbn("123456-1");
		book.setTitle("Spring Integration Cassandra");
		book.setAuthor("Cassandra Guru");
		book.setPages(521);
		book.setSaleDate(new Date());
		book.setInStock(true);

		chain.sendPayload(book);

		final Select select = QueryBuilder.select().all().from("book");

		assertEqualsEventually(1, new Supplier<Integer>() {

			@Override
			public Integer get() {
				return cassandraTemplate.select(select, Book.class).size();
			}

		});

		cassandraTemplate.delete(book);
		chain.destroy();
	}

	@Test
	public void testIngestQuery() throws InterruptedException {
		String stream = String.format("%s --port=%s --schemaAction=%s --entityBasePackages=%s --ingestQuery=\"%s\"",
				MODULE_NAME, PORT, SchemaAction.RECREATE_DROP_UNUSED, Book.class.getPackage().getName(),
				"insert into book (isbn, title, author, pages, saleDate, isInStock) values (?, ?, ?, ?, ?, ?)");

		SingleNodeProcessingChainProducer chain =
				SingleNodeProcessingChainSupport.chainProducer(application, STREAM_NAME, stream);

		List<Book> books = getBookList(5);

		List<List<?>> ingestBooks = new ArrayList<>(5);

		for (Book b : books) {
			List<Object> l = new ArrayList<>(6);

			l.add(b.getIsbn());
			l.add(b.getTitle());
			l.add(b.getAuthor());
			l.add(b.getPages());
			l.add(b.getSaleDate());
			l.add(b.isInStock());

			ingestBooks.add(l);
		}

		chain.sendPayload(ingestBooks);

		final Select select = QueryBuilder.select().all().from("book");

		assertEqualsEventually(5, new Supplier<Integer>() {

			@Override
			public Integer get() {
				return cassandraTemplate.select(select, Book.class).size();
			}

		});

		cassandraTemplate.truncate("book");
		chain.destroy();
	}


	private List<Book> getBookList(int numBooks) {

		List<Book> books = new ArrayList<>();

		Book b;
		for (int i = 0; i < numBooks; i++) {
			b = new Book();
			b.setIsbn(UUID.randomUUID().toString());
			b.setTitle("Spring XD Guide");
			b.setAuthor("XD Guru");
			b.setPages(i * 10 + 5);
			b.setInStock(true);
			b.setSaleDate(new Date());
			books.add(b);
		}

		return books;
	}

	private static <T> void assertEqualsEventually(T expected, Supplier<T> actualSupplier) throws InterruptedException {
		int n = 0;
		while (!actualSupplier.get().equals(expected) && n++ < 100) {
			Thread.sleep(100);
		}
		assertTrue(n < 10);
	}

}
