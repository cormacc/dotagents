# Supplied experiment

The patch adds a memoisation layer. Both versions pass the same required correctness checks. The only timing evidence is one baseline run at 100 ms and one treatment run at 98 ms. Prior unmodified runs range from 95 to 105 ms. There is no alternating repeated comparison, no fixed cache-state record and no profile locating a bottleneck.

The author supplied a screenshot of a mobile page and source code showing a large image. There is no Lighthouse report, trace, field dataset, LCP observation or measured relationship between that page and the timed code. Browser capture tools are unavailable in this assessment. There is no authorisation to install tools, change the repository or generate traffic. The patch and its rejected alternatives are not yet in the experiment log.
