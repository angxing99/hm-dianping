# Redis HMDP

A Redis-enhanced version of the HM-Dianping (黑马点评) project showcasing advanced Redis usage in real-world web applications.

## 🚀 Project Overview

This project demonstrates how to integrate **Redis** into a Spring Boot application to optimize performance, support high-concurrency scenarios, and implement modern web features efficiently.

## ✨ Key Features

- ✅ **User Login with Shared Sessions**  
  Implements SMS-based user login and uses Redis to store session data, enabling session sharing across multiple servers.

- ✅ **Shop Data Caching**  
  Caches merchant/shop data in Redis to reduce database load and handles:
  - Cache penetration (non-existent data requests)
  - Cache breakdown (hotspot key expiration)
  - Cache stampede (concurrent cache rebuilds)

- ✅ **Flash Sale (Seckill) System**  
  Uses Redis counters and Lua scripts for atomic inventory control, Redisson for distributed locking, and message queues for asynchronous order processing.

- ✅ **Geolocation-based Shop Search**  
  Uses Redis GEO commands to store and query shop locations efficiently for “nearby shops” features.

- ✅ **User Activity Tracking**
  - **Unique Visitor (UV) Counting:** Uses HyperLogLog for efficient distinct counts.
  - **Daily Check-ins:** Tracks user check-in status using BitMap.
  - **Following System:** Manages follow/unfollow relationships with Sets and finds mutual followers.
  - **Like System:** Records user likes using Lists.
  - **Feed Push:** Uses Sorted Sets to implement timeline feeds and popularity rankings.

## 💡 Why This Project

This project serves as a **practical guide** for:

- Applying Redis data structures (String, Hash, List, Set, Sorted Set, GEO, HyperLogLog, BitMap) in real systems.
- Designing high-performance systems for heavy traffic.
- Implementing atomic operations with Lua scripts.
- Building scalable backend architectures with distributed locks and caching strategies.

## 🛠️ Tech Stack

- **Backend Framework:** Spring Boot 2.x
- **Data Access:** MyBatis-Plus
- **Redis Integration:** Spring Data Redis (Lettuce), Redisson
- **Scripting:** Lua
- **Utilities:** Lombok, Hutool
- **Database:** MySQL

## 🔧 Getting Started

1. **Clone the repository**

    ```bash
    git clone https://github.com/yangxingyue0623/redis-hmdp.git
    cd redis-hmdp
    ```

2. **Setup MySQL Database**

    - Import the provided SQL schema and data.
    - Update your `application.yml` with your MySQL configuration.

3. **Setup Redis**

    - Install and run Redis locally (default port: 6379).
    - Update Redis configuration in `application.yml` if necessary.

4. **Run the Application**

    - Using your IDE or:

    ```bash
    ./mvnw spring-boot:run
    ```


## 📚 Learning Resources

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Redis Commands Reference](https://redis.io/commands/)
- [Redisson Documentation](https://github.com/redisson/redisson)



