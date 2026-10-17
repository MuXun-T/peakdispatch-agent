local count = redis.call('INCR', KEYS[1])
local windowMs = tonumber(ARGV[2])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], windowMs)
end
local ttl = redis.call('PTTL', KEYS[1])
if count <= tonumber(ARGV[1]) then return 0 end
return math.max(1, ttl)
