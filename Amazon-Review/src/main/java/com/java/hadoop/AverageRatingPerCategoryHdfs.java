package com.java.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.IOException;
import java.util.ArrayList;

public class AverageRatingPerCategoryHdfs {

    public static class RatingMapper extends Mapper<Object, Text, Text, DoubleWritable> {
        private Text category = new Text();
        private DoubleWritable rating = new DoubleWritable();

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString();

            // Skip header
            if (line.contains("asin")) return;

            String[] fields = line.split(",");

            if (fields.length == 5) {
                try {
                    category.set(fields[3]); // category
                    double currentRating = Double.parseDouble(fields[2]); // rating
                    rating.set(currentRating);
                    context.write(category, rating);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid rating: " + fields[2]);
                }
            }
        }
    }

    public static class RatingReducer extends Reducer<Text, DoubleWritable, Text, Text> {
        public void reduce(Text key, Iterable<DoubleWritable> values, Context context) throws IOException, InterruptedException {
            ArrayList<Double> ratings = new ArrayList<>();
            double sum = 0;

            for (DoubleWritable val : values) {
                double rating = val.get();
                ratings.add(rating);
                sum += rating;
            }

            int count = ratings.size();
            if (count == 0) return;

            double mean = sum / count;

            // Calculate standard deviation
            double sumSquares = 0;
            for (double r : ratings) {
                sumSquares += Math.pow(r - mean, 2);
            }
            double stdDev = Math.sqrt(sumSquares / count);

            // Outlier Detection: Count ratings > 2 * stdDev from mean
            int outliers = 0;
            for (double r : ratings) {
                if (Math.abs(r - mean) > 2 * stdDev) {
                    outliers++;
                }
            }

            String output = String.format("Mean: %.2f, StdDev: %.2f, Outliers: %d", mean, stdDev, outliers);
            context.write(key, new Text(output));
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        Job job = Job.getInstance(conf, "Average Rating Per Category with StdDev & Outliers");

        job.setJarByClass(AverageRatingPerCategoryHdfs.class);
        job.setMapperClass(RatingMapper.class);
        job.setReducerClass(RatingReducer.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(DoubleWritable.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));   // Input HDFS path
        FileOutputFormat.setOutputPath(job, new Path(args[1])); // Output HDFS path

        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
