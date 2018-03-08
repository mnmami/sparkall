package org.sparkall

import java.util

import com.google.common.collect.ArrayListMultimap
import com.mongodb.spark.config.ReadConfig
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.sparkall.Helpers._

import scala.collection.mutable
import scala.collection.mutable.{HashMap, Set}

class SparkExecutor(sparkURI: String, mappingsFile: String) extends QueryExecutor[DataFrame] {

    def query(sources : Set[(HashMap[String, String], String, String)],
               optionsMap: HashMap[String, Map[String, String]],
               toJoinWith: Boolean,
               star: String,
               prefixes: Map[String, String],
               select: util.List[String],
               star_predicate_var: mutable.HashMap[(String, String), String],
               neededPredicates: Set[String],
               filters: ArrayListMultimap[String, (String, String)],
               leftJoinTransformations: (String, Array[String]),
               rightJoinTransformations: Array[String],
               joinPairs: Map[(String,String), String]
        ): (DataFrame, Integer) = {

        Logger.getLogger("org").setLevel(Level.OFF)
        Logger.getLogger("akka").setLevel(Level.OFF)

        val spark = SparkSession.builder.master(sparkURI).appName("Sparkall").getOrCreate;

        var finalDF : DataFrame = null
        var datasource_count = 0

        for (s <- sources) {
            println("\nNEXT SOURCE...")
            datasource_count += 1 // in case of multiple relevant data sources to union

            val attr_predicate = s._1
            println("Star: " + star)
            println("attr_predicate: " + attr_predicate)
            val sourcePath = s._2
            val sourceType = getTypeFromURI(s._3)
            val options = optionsMap(sourcePath)

            // TODO: move to another class better
            var columns = getSelectColumnsFromSet(attr_predicate, omitQuestionMark(star), prefixes, select, star_predicate_var, neededPredicates)

            println("Relevant source (" + datasource_count + ") is: [" + sourcePath + "] of type: [" + sourceType + "]")

            println("...from which columns (" + columns + ") are going to be projected")
            println("...with the following configuration options: " + options)

            if (toJoinWith) { // That kind of table that is the 1st or 2nd operand of a join operation
                val id = getID(sourcePath, mappingsFile)
                println("...is to be joined with using the ID: " + omitQuestionMark(star) + "_" + id + " (obtained from subjectMap)")
                if(columns == "") {
                    //println("heeey id = " + id + " star " + star)
                    columns = id + " AS " + omitQuestionMark(star) + "_ID"
                } else
                    columns = columns + "," + id + " AS " + omitQuestionMark(star) + "_ID"
            }

            println("star_predicate_var: " + star_predicate_var)
            println("sourceType: " + sourceType)

            var df : DataFrame = null
            sourceType match {
                case "csv" => df = spark.read.options(options).csv(sourcePath)
                case "parquet" => df = spark.read.options(options).parquet(sourcePath)
                case "cassandra" =>
                    //spark.conf.set("spark.cassandra.connection.host", "127.0.0.1")
                    //println("CASSANDRA CONF:" + spark.conf.get("spark.cassandra.connection.host"))
                    df = spark.read.format("org.apache.spark.sql.cassandra").options(options).load
                case "mongodb" =>
                    //spark.conf.set("spark.mongodb.input.uri", "mongodb://127.0.0.1/test.myCollection")
                    val values = options.values.toList
                    val mongoConf = if (values.length == 4) makeMongoURI(values(0), values(1), values(2), values(3))
                                    else makeMongoURI(values(0), values(1), values(2), null)
                        val mongoOptions: ReadConfig = ReadConfig(Map("uri" -> mongoConf, "partitioner" -> "MongoPaginateBySizePartitioner"))
                    df = spark.read.format("com.mongodb.spark.sql").options(mongoOptions.asOptions).load
                case "jdbc" =>
                    df = spark.read.format("jdbc").options(options).load()
                case _ =>
            }

            df.createOrReplaceTempView("table")
            var newDF = spark.sql("SELECT " + columns + " FROM table")

            if(datasource_count == 1) {
                finalDF = newDF

            } else {
                finalDF = finalDF.union(newDF)
            }

            // Transformations
            if (leftJoinTransformations != null && leftJoinTransformations._2 != null) {
                val column: String = leftJoinTransformations._1
                println("leftJoinTransformations: " + column + " - " + leftJoinTransformations._2.mkString("."))
                val ns_pred = get_NS_predicate(column)
                val ns = prefixes(ns_pred._1)
                val pred = ns_pred._2
                val col = omitQuestionMark(star) + "_" + pred + "_" + ns
                finalDF = transform(finalDF, col, leftJoinTransformations._2)

            }
            if (rightJoinTransformations != null && !rightJoinTransformations.isEmpty) {
                println("rightJoinTransformations: " + rightJoinTransformations.mkString("_"))
                val col = omitQuestionMark(star) + "_ID"
                finalDF = transform(finalDF, col, rightJoinTransformations)
            }

        }

        println("\n- filters: " + filters + " ======= " + star)

        var whereString = ""

        var nbrOfFiltersOfThisStar = 0

        val it = filters.keySet().iterator()
        while (it.hasNext) {
            val value = it.next()
            val predicate = star_predicate_var.
                filter(t => t._2 == value).
                keys. // To obtain (star, predicate) pairs having as value the FILTER'ed value
                filter(t => t._1 == star).
                map(f => f._2).toList

            if (predicate.nonEmpty) {
                val ns_p = get_NS_predicate(predicate.head) // Head because only one value is expected to be attached to the same star an same (object) variable
                val column = omitQuestionMark(star) + "_" + ns_p._2 + "_" + prefixes(ns_p._1)
                println("column: " + column)

                nbrOfFiltersOfThisStar = filters.get(value).size()

                val conditions = filters.get(value).iterator()
                while (conditions.hasNext) {
                    val operand_value = conditions.next()
                    println("operand_value: " + operand_value)
                    whereString = column + operand_value._1 + operand_value._2
                    println("whereString: " + whereString)
                    finalDF = finalDF.filter(whereString)
                }
                //finalDF.show()
            }
        }

        println(s"Number of filters of this star is: $nbrOfFiltersOfThisStar")

        (finalDF, nbrOfFiltersOfThisStar)
    }

    def transform(df: DataFrame, column: String, transformationsArray : Array[String]): DataFrame = {

        var ndf : DataFrame = df
        for (t <- transformationsArray) {
            println("Transformation next: " + t)
            t match {
                case "toInt" =>
                    println("TOINT found")
                    ndf = ndf.withColumn(column, ndf(column).cast(IntegerType))
                    // From SO: values not castable will become null
                case s if s.contains("scl") =>
                    val scaleValue = s.replace("scl","").trim.stripPrefix("(").stripSuffix(")")
                    println("SCL found: " + scaleValue)
                    val operation = scaleValue.charAt(0)
                    operation match {
                        case '+' => ndf = ndf.withColumn(column, ndf(column) + scaleValue.substring(1).toInt)
                        case '-' => ndf = ndf.withColumn(column, ndf(column) - scaleValue.substring(1).toInt)
                        case '*' => ndf = ndf.withColumn(column, ndf(column) * scaleValue.substring(1).toInt)
                    }
                case s if s.contains("skp") =>
                    val skipValue = s.replace("skp","").trim.stripPrefix("(").stripSuffix(")")
                    println("SKP found: " + skipValue)
                    ndf = ndf.filter(!ndf(column).equalTo(skipValue))
                case s if s.contains("substit") =>
                    val replaceValues = s.replace("substit","").trim.stripPrefix("(").stripSuffix(")").split("\\,")
                    val valToReplace = replaceValues(0)
                    val valToReplaceWith = replaceValues(1)
                    println("SUBSTIT found: " + replaceValues.mkString(" -> "))
                    ndf = ndf.withColumn(column, when(col(column).equalTo(valToReplace), valToReplaceWith))
                    //ndf = df.withColumn(column, when(col(column) === valToReplace, valToReplaceWith).otherwise(col(column)))
                case s if s.contains("replc") =>
                    val replaceValues = s.replace("replc","").trim.stripPrefix("(").stripSuffix(")").split("\\,")
                    val valToReplace = replaceValues(0).replace("\"","")
                    val valToReplaceWith = replaceValues(1).replace("\"","")
                    println("REPLC found: " + replaceValues.mkString(" -> ") + " on column: " + column)
                    ndf = ndf.withColumn(column, when(col(column).contains(valToReplace), regexp_replace(ndf(column), valToReplace, valToReplaceWith)))
                case s if s.contains("prefix") =>
                    val prefix = s.replace("prfix","").trim.stripPrefix("(").stripSuffix(")")
                    println("PREFIX found: " + prefix)
                    ndf = ndf.withColumn(column, concat(lit(prefix), ndf.col(column)))
                case s if s.contains("postfix") =>
                    val postfix = s.replace("postfix","").trim.stripPrefix("(").stripSuffix(")")
                    println("POSTFIX found: " + postfix)
                    ndf = ndf.withColumn(column, concat(lit(ndf.col(column), postfix)))
                case _ =>
            }
        }

        ndf
    }

    def join(joins: ArrayListMultimap[String, (String, String)], prefixes: Map[String, String], star_df: Map[String, DataFrame]): DataFrame = {
        import scala.collection.JavaConversions._
        import scala.collection.mutable.ListBuffer

        var pendingJoins = mutable.Queue[(String, (String, String))]()
        val seenDF : ListBuffer[(String,String)] = ListBuffer()
        var firstTime = true
        val join = " x "
        var jDF : DataFrame = null

        val it = joins.entries.iterator
        while ({it.hasNext}) {
            val entry = it.next

            val op1 = entry.getKey
            val op2 = entry.getValue._1
            val jVal = entry.getValue._2
            // TODO: add omitQuestionMark and omit it from the next

            println(s"-> GOING TO JOIN ($op1 $join $op2) USING $jVal...")

            val njVal = get_NS_predicate(jVal)
            val ns = prefixes(njVal._1)

            println("njVal: " + ns)

            it.remove

            val df1 = star_df(op1)
            val df2 = star_df(op2)

            if (firstTime) { // First time look for joins in the join hashmap
                println("...that's the FIRST JOIN")
                seenDF.add((op1, jVal))
                seenDF.add((op2, "ID"))
                firstTime = false

                // Join level 1
                jDF = df1.join(df2, df1.col(omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns).equalTo(df2(omitQuestionMark(op2) + "_ID")))
                println("...done")
            } else {
                val dfs_only = seenDF.map(_._1)
                println(s"EVALUATING NEXT JOIN \n ...checking prev. done joins: $dfs_only")
                if (dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                    println("...we can join (this direction >>)")

                    val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                    val rightJVar = omitQuestionMark(op2) + "_ID"
                    jDF = jDF.join(df2, jDF.col(leftJVar).equalTo(df2.col(rightJVar)))

                    seenDF.add((op2,"ID"))

                    //println("Nbr: " + jDF.count)
                    //jDF.show()
                } else if (!dfs_only.contains(op1) && dfs_only.contains(op2)) {
                    println("...we can join (this direction >>)")

                    val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                    val rightJVar = omitQuestionMark(op2) + "_ID"
                    jDF = df1.join(jDF, df1.col(leftJVar).equalTo(jDF.col(rightJVar)))

                    seenDF.add((op1,jVal))

                    //println("Nbr: " + jDF.count)
                    //jDF.show()
                } else if (!dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                    println("...no join possible -> GOING TO THE QUEUE")
                    pendingJoins.enqueue((op1, (op2, jVal)))
                }
            }
        }

        while (pendingJoins.nonEmpty) {
            println("ENTERED QUEUED AREA: " + pendingJoins)
            val dfs_only = seenDF.map(_._1)

            val e = pendingJoins.head

            val op1 = e._1
            val op2 = e._2._1
            val jVal = e._2._2

            var njVal = get_NS_predicate(jVal)
            var ns = prefixes(njVal._1)

            println(s"-> Joining ($op1 $join $op2 + ) using $jVal...")

            val df1 = star_df(op1)
            val df2 = star_df(op2)

            if (dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = jDF.join(df2, jDF.col(leftJVar).equalTo(df2.col(rightJVar))) // deep-left
                //jDF = df2.join(jDF, jDF.col(leftJVar).equalTo(df2.col(rightJVar)))

                seenDF.add((op2,"ID"))
            } else if (!dfs_only.contains(op1) && dfs_only.contains(op2)) {
                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = jDF.join(df1, df1.col(leftJVar).equalTo(jDF.col(rightJVar))) // deep-left
                //jDF = df1.join(jDF, df1.col(leftJVar).equalTo(jDF.col(rightJVar)))

                seenDF.add((op1,jVal))
            } else if (!dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                pendingJoins.enqueue((op1, (op2, jVal)))
            }

            pendingJoins = pendingJoins.tail
        }

        jDF
    }

    def joinReordered(joins: ArrayListMultimap[String, (String, String)], prefixes: Map[String, String], star_df: Map[String, DataFrame], startingJoin: (String, (String, String)), starWeights: Map[String, Double]): DataFrame = {
        import scala.collection.JavaConversions._
        import scala.collection.mutable.ListBuffer

        var pendingJoins = mutable.Queue[(String, (String, String))]()
        val seenDF : ListBuffer[(String,String)] = ListBuffer()
        val joinSymbol = " x "
        var jDF : DataFrame = null

        val op1 = startingJoin._1
        val op2 = startingJoin._2._1
        val jVal = startingJoin._2._2
        val njVal = get_NS_predicate(jVal)
        val ns = prefixes(njVal._1)
        val df1 = star_df(op1)
        val df2 = star_df(op2)

        println(s"-> DOING FIRST JOIN ($op1 $joinSymbol $op2 ) USING $jVal (namespace: $ns)")

        seenDF.add((op1, jVal))
        seenDF.add((op2, "ID")) // TODO: implement join var in the right side too

        // Join level 1
        val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
        val rightJVar = omitQuestionMark(op2) + "_ID"
        jDF = df1.join(df2, df1.col(leftJVar).equalTo(df2(rightJVar)))

        joins.remove(startingJoin._1,(startingJoin._2._1, startingJoin._2._2))

        println("...done!")

        val it = joins.entries.iterator
        while ({it.hasNext}) {
            val entry = it.next

            val op1 = entry.getKey
            val op2 = entry.getValue._1
            val jVal = entry.getValue._2
            // TODO: add omitQuestionMark and omit it from the next

            val njVal = get_NS_predicate(jVal)
            val ns = prefixes(njVal._1)
            println(s"-> NEXT, GOING TO JOIN ($op1 $joinSymbol $op2 ) USING $jVal (namespace: $ns)")

            it.remove

            val df1 = star_df(op1)
            val df2 = star_df(op2)

            val dfs_only = seenDF.map(_._1)
            println("...checking the prev. joined DataFrames: " + dfs_only)
            if (dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                println("...aaand we can join (this direction >>) ")

                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = jDF.join(df2, jDF.col(leftJVar).equalTo(df2.col(rightJVar)))

                seenDF.add((op2,"ID"))

                //println("Nbr: " + jDF.count)
                //jDF.show()
            } else if (!dfs_only.contains(op1) && dfs_only.contains(op2)) {
                println("...aaand we can join (this direction <<) ")

                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = df1.join(jDF, df1.col(leftJVar).equalTo(jDF.col(rightJVar)))

                seenDF.add((op1,jVal))

                //println("Nbr: " + jDF.count)
                //jDF.show()
            } else if (!dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                println("...no join possible -> GOING TO THE QUEUE")
                pendingJoins.enqueue((op1, (op2, jVal)))
            }
            //}
        }

        while (pendingJoins.nonEmpty) {
            println("ENTERED QUEUED AREA: " + pendingJoins)
            val dfs_only = seenDF.map(_._1)

            val e = pendingJoins.head

            val op1 = e._1
            val op2 = e._2._1
            val jVal = e._2._2

            val njVal = get_NS_predicate(jVal)
            val ns = prefixes(njVal._1)

            println(s"-> Joining ($op1 $joinSymbol $op2 + ) using $jVal...")

            val df1 = star_df(op1)
            val df2 = star_df(op2)

            if (dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = jDF.join(df2, jDF.col(leftJVar).equalTo(df2.col(rightJVar))) // deep-left

                seenDF.add((op2,"ID"))
            } else if (!dfs_only.contains(op1) && dfs_only.contains(op2)) {
                val leftJVar = omitQuestionMark(op1) + "_" + omitNamespace(jVal) + "_" + ns
                val rightJVar = omitQuestionMark(op2) + "_ID"
                jDF = jDF.join(df1, df1.col(leftJVar).equalTo(jDF.col(rightJVar))) // deep-left

                seenDF.add((op1,jVal))
            } else if (!dfs_only.contains(op1) && !dfs_only.contains(op2)) {
                pendingJoins.enqueue((op1, (op2, jVal)))
            }

            pendingJoins = pendingJoins.tail
        }

        jDF
    }

    def project(jDF: DataFrame, columnNames: Seq[String]): DataFrame = {
        jDF.select(columnNames.head, columnNames.tail: _*)
    }

    def schemaOf(jDF: DataFrame) = {
        jDF.printSchema()
    }

    def count(jDF: DataFrame): Long = {
        jDF.count()
    }
}