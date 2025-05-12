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
import java.util.Collections;

public class AverageRatingPerCategory {

    public static class RatingMapper extends Mapper<Object, Text, Text, DoubleWritable> {
        private Text categoryProduct = new Text();
        private DoubleWritable rating = new DoubleWritable();

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString();

            // Skip header
            if (line.contains("asin")) return;

            String[] fields = line.split(",");

            if (fields.length == 5) {
                try {
                    String category = fields[3];
                    String product = fields[1]; // Assume product ID
                    double currentRating = Double.parseDouble(fields[2]);

                    categoryProduct.set(category + "," + product); // Group by category + product
                    rating.set(currentRating);
                    context.write(categoryProduct, rating);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid rating: " + fields[2]);
                }
            }
        }
    }

    public static class RatingReducer extends Reducer<Text, DoubleWritable, Text, Text> {
        public void reduce(Text key, Iterable<DoubleWritable> values, Context context) throws IOException, InterruptedException {
            ArrayList<Double> ratings = new ArrayList<>();

            for (DoubleWritable val : values) {
                ratings.add(val.get());
            }

            int count = ratings.size();
            if (count == 0) return;

            // Sort ratings to compute IQR
            Collections.sort(ratings);

            double q1 = percentile(ratings, 25);
            double q3 = percentile(ratings, 75);
            double iqr = q3 - q1;

            double lowerBound = q1 - 1.5 * iqr;
            double upperBound = q3 + 1.5 * iqr;

            // Filter outliers
            ArrayList<Double> filteredRatings = new ArrayList<>();
            for (double r : ratings) {
                if (r >= lowerBound && r <= upperBound) {
                    filteredRatings.add(r);
                }
            }

            if (filteredRatings.size() == 0) return;

            // Calculate mean without outliers
            double sum = 0;
            for (double r : filteredRatings) {
                sum += r;
            }
            double mean = sum / filteredRatings.size();

            String output = String.format("Cleaned Mean: %.2f, Original Count: %d, Cleaned Count: %d", mean, ratings.size(), filteredRatings.size());
            context.write(key, new Text(output));
        }

        private double percentile(ArrayList<Double> sortedList, double percentile) {
            if (sortedList.isEmpty()) return 0;
            int index = (int) Math.ceil(percentile / 100.0 * sortedList.size()) - 1;
            return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        Job job = Job.getInstance(conf, "Average Rating Per Category and Product with Outlier Removal");

        job.setJarByClass(AverageRatingPerCategory.class);
        job.setMapperClass(RatingMapper.class);
        job.setReducerClass(RatingReducer.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(DoubleWritable.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
