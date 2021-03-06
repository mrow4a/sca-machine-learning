/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mrow4a.spark.java;

import mrow4a.spark.java.alg.EmailParser;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.SparkConf;

import java.util.Collection;
import java.util.TreeSet;
import mrow4a.spark.java.alg.Shingling;
import mrow4a.spark.java.alg.JaccardSimilarity;

import java.time.Duration;
import java.time.Instant;

public final class FindingSimilarDocsBasicJob {

    public static void main(String[] args) throws Exception {
        SparkConf conf = new SparkConf()
                .setAppName("FindingSimilarDocsBasicJob")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .setMaster("local[*]").set("spark.executor.memory", "1g");
        SparkSession spark = SparkSession
                .builder()
                .config(conf)
                .getOrCreate();
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(spark.sparkContext());

        // multiple = /home/mrow4a/Projects/machine-learning/finding-similar-docs/datasets/mini_newsgroups/*/**
        // single dir = /home/mrow4a/Projects/machine-learning/finding-similar-docs/datasets/mini_newsgroups/alt.atheism/*
        if (args.length < 1) {
            System.err.println();
            System.err.println("Usage: FindingSimilarDocsBasicJob <file>");
            System.err.println();
            System.exit(1);
        }

        Instant start = Instant.now();

        /*
         * Set shingle length to 5 as it is good for emails
         */
        Integer shingleLengthEmail = 5;

        /*
         * Set similarity for email to 0.1, which should indicate if responses or similar topic
         */
        Double similarityThresholdEmail = 0.3;

        /*
         * Create shingles unique list
         */
        JavaPairRDD<String, Collection<Integer>> shingles = jsc
                /*
                 * Read directory contents to RDD with key-value, key -> filename and value -> contents
                 */
                .wholeTextFiles(args[0])
                /*
                 * Parse emails to remove headers
                 */
                .mapToPair(filenameContentsPair -> new Tuple2<>(
                        filenameContentsPair._1, // filename
                        EmailParser.parse(filenameContentsPair._2)) // shingles of contents
                )
                /*
                 * Compute shingles hashset for each document
                 */
                .mapToPair(filenameContentsPair -> new Tuple2<>(
                        filenameContentsPair._1, // filename
                        Shingling.getShingles(filenameContentsPair._2, shingleLengthEmail)) // shingles of contents
                )
                /*
                 * Cache, otherwise we will end up with MapReduce like behaviour
                */
                .cache();

        JavaPairRDD<Tuple2<String, Collection<Integer>>, Tuple2<String, Collection<Integer>>> uniqueFileShinglesPairs = shingles
                /*
                 * Combine each file with each file for similarity comparison
                 */
                .cartesian(shingles)
                /*
                 * Ensure uniqueness of pairs
                 */
                .filter(setsPair -> setsPair._1._1.hashCode() < setsPair._2._1.hashCode()).cache();


        JavaPairRDD<Tuple2<String, String>, Float> similarities = uniqueFileShinglesPairs
                /*
                 * Compute similarity
                 */
                .mapToPair(setsPair -> new Tuple2<>(
                        new Tuple2<>(setsPair._1._1, setsPair._2._1), // filename-filename pair
                        JaccardSimilarity.compute(setsPair._1._2, setsPair._2._2)
                ))
                .filter(filesSimilarityPaid -> filesSimilarityPaid._2 > similarityThresholdEmail).cache();

        long countSim = similarities.count();
        long countUniqPairs = uniqueFileShinglesPairs.count();

        System.err.println();

        for (Tuple2<Tuple2<String, String>, Float> tuple :  similarities.collect()) {
            System.out.println(tuple._1() + ": " + tuple._2());
        }

        Instant end = Instant.now();

        System.err.println();
        System.out.println("Found similar document pairs ["+
                countSim + "/" + countUniqPairs + "] with similarity threshold [" + similarityThresholdEmail
                + "] and shingle lenght [" + shingleLengthEmail + "]");
        System.out.println("Time: "+
                Duration.between(start, end).toMillis() +" milliseconds");
        System.err.println();

        spark.stop();
    }
}
